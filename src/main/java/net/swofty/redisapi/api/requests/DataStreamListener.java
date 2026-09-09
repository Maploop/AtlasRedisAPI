package net.swofty.redisapi.api.requests;

import net.swofty.redisapi.api.ChannelRegistry;
import net.swofty.redisapi.api.RedisAPI;
import net.swofty.redisapi.api.RedisExecutors;
import net.swofty.redisapi.events.RedisMessagingReceiveInterface;
import net.swofty.redisapi.util.RedisParsableMessage;
import org.jetbrains.annotations.ApiStatus;
import org.json.JSONObject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal use, not for outside API
 */
@ApiStatus.Internal
public class DataStreamListener implements RedisMessagingReceiveInterface {
    private static final Logger LOGGER = Logger.getLogger(DataStreamListener.class.getName());

    @Override
    public void onMessage(String channel, String message) {
        RedisParsableMessage msg = RedisParsableMessage.parse(message);
        DataRequest.StreamType type = DataRequest.StreamType.valueOf(msg.get("stream", "NONE"));
        String key = msg.get("key", "NONE");
        String id = msg.get("id", "NONE");
        String sender = msg.get("sender", "NONE");
        // optJSONObject, not getJSONObject: a message without a payload used to throw here.
        JSONObject data = msg.getJson().optJSONObject("data");

        switch (type) {
            case REQUEST -> {
                DataRequestResponder responder = DataRequestResponder.get(key);
                if (responder == null) return;

                // Responders are free to hit the database or wait on a round trip, so they get
                // their own pool rather than running on the thread that read the message.
                JSONObject requestData = data == null ? new JSONObject() : data;
                RedisExecutors.submit(RedisExecutors.HANDLERS, "handler",
                        () -> respond(responder, key, id, requestData, sender));
            }
            // A null payload stays null so callers keep seeing it as a failed request.
            case RESPONSE -> DataRequest.receive(id, data);
        }
    }

    private void respond(DataRequestResponder responder, String key, String id, JSONObject data, String sender) {
        JSONObject response = null;

        try {
            response = responder.respond(data);
        } catch (Throwable error) {
            LOGGER.log(Level.SEVERE, "Data request responder for key " + key + " failed", error);
        }

        // Reply even when the responder blew up or returned nothing, so the requester fails fast
        // rather than waiting out its whole timeout for a response that is never coming.
        JSONObject responseJson = new JSONObject();
        responseJson.put("id", id);
        responseJson.put("sender", RedisAPI.getInstance().getFilterId());
        responseJson.put("stream", DataRequest.StreamType.RESPONSE.name());
        responseJson.put("key", key);
        if (response != null)
            responseJson.put("data", response);

        RedisAPI.getInstance().publishMessage(sender, ChannelRegistry.getFromName(DataRequest.CHANNEL),
                RedisParsableMessage.build(responseJson).formatForSend());
    }
}
