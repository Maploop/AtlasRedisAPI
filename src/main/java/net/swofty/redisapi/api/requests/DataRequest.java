package net.swofty.redisapi.api.requests;

import net.swofty.redisapi.api.ChannelRegistry;
import net.swofty.redisapi.api.RedisAPI;
import net.swofty.redisapi.api.RedisExecutors;
import net.swofty.redisapi.util.RedisParsableMessage;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DataRequest {
    /**
     * The internal channel every request and response travels over. Registered automatically by
     * {@link RedisAPI#startListeners()}.
     */
    public static final String CHANNEL = "internal-data-request";

    /**
     * How long to wait for a response, measured from the moment the request actually reaches Redis
     * rather than from the {@link #await()} call.
     */
    private static final long TIMEOUT_MS = 1_000;

    private static final Map<String, CompletableFuture<JSONObject>> PENDING = new ConcurrentHashMap<>();

    private final String id;
    private final String filter;
    private final String key;
    private final JSONObject data;

    /**
     * Create a new data request to get a specific object of data from a specific server.
     * @param filterID the destination of the request. Can be "all" for all listeners.
     * @param key a unique key for the data request.
     * @param data optional request payload, may be null for an empty payload.
     */
    public DataRequest(String filterID, String key, @Nullable JSONObject data) {
        this.id = UUID.randomUUID().toString();
        this.filter = filterID;
        this.key = key;
        this.data = data == null ? new JSONObject() : data;
    }

    /**
     * Publishes the request and returns a future that completes the moment a response arrives
     * (or after {@value #TIMEOUT_MS}ms with a null response, same as before) - without ever
     * blocking or parking a thread while waiting. The future is fulfilled by {@link #receive}
     * when the corresponding {@link DataRequestResponder} response comes back over Redis.
     *
     * @return a {@link DataResponse} which must be handled in async.
     */
    public CompletableFuture<DataResponse> await() {
        long start = System.currentTimeMillis();

        CompletableFuture<JSONObject> responseFuture = new CompletableFuture<>();
        PENDING.put(id, responseFuture);

        JSONObject request = new JSONObject();
        request.put("id", id);
        request.put("key", key);
        request.put("data", data);
        request.put("sender", RedisAPI.getInstance().getFilterId()); // We assume your FilterID is set before using this.
        request.put("stream", StreamType.REQUEST.name());

        return RedisAPI.getInstance()
                .publishMessage(filter, ChannelRegistry.getFromName(CHANNEL),
                        RedisParsableMessage.build(request).formatForSend())
                // The timeout is armed only once the publish has reached Redis. Arming it at call
                // time billed connection queueing against the response budget, so a busy server
                // timed out requests the remote end had not even seen yet.
                .thenComposeAsync(
                        ignored -> responseFuture.completeOnTimeout(null, TIMEOUT_MS, TimeUnit.MILLISECONDS),
                        RedisExecutors.completions())
                // handleAsync, not whenComplete: it also turns a failed publish into a null
                // response, so a request that never left the box fails fast instead of waiting
                // out the full timeout. Cleanup happens on every path, and the caller's
                // continuations run on a dedicated pool rather than inline on the thread that
                // read the response off Redis.
                .handleAsync((response, error) -> {
                    PENDING.remove(id);
                    return new DataResponse(response, System.currentTimeMillis() - start);
                }, RedisExecutors.completions());
    }

    /**
     * Called by whatever listens for incoming Redis messages when a {@code RESPONSE}-stream
     * message with a matching id comes back. This is the only thing that completes a pending
     * future - replaces the old pattern of putting straight into a shared map and having callers
     * poll it.
     *
     * @param id the request id this response is answering.
     * @param response the response payload.
     */
    public static void receive(String id, JSONObject response) {
        CompletableFuture<JSONObject> future = PENDING.remove(id);
        if (future != null)
            future.complete(response);
    }

    public enum StreamType {
        REQUEST,
        RESPONSE
    }
}
