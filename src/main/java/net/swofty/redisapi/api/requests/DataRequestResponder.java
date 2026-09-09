package net.swofty.redisapi.api.requests;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class DataRequestResponder {
    public static final Map<String, DataRequestResponder> RESPONDERS = new ConcurrentHashMap<>();

    private final Function<JSONObject, JSONObject> callback;

    protected DataRequestResponder(Function<JSONObject, JSONObject> callback) {
        this.callback = callback;
    }

    /**
     * Respond to a received request. This method should not be handled manually, the server's listener calls this method automatically.
     * @param request the request data as a {@link JSONObject}
     * @return the response data as a {@link JSONObject}
     */
    public JSONObject respond(JSONObject request) {
        return this.callback.apply(request);
    }

    /**
     * Create a responder to a data request, so when a data request with a specified key gets sent to this server, it'll respond back.
     * @param key the server's unique key.
     * @param callback callback function, the input is the request data as a {@link JSONObject} and the output must be your response data as a {@link JSONObject}.
     * @return the responder you just created.
     */
    public static DataRequestResponder create(String key, Function<JSONObject, JSONObject> callback) {
        DataRequestResponder responder = new DataRequestResponder(callback);
        RESPONDERS.put(key, responder);
        return responder;
    }

    /**
     * Get a responder by its unique key.
     * @param key The responder's unique key.
     * @return The {@link DataRequestResponder} which is stored with that unique key.
     */
    public static DataRequestResponder get(String key) {
        return RESPONDERS.get(key);
    }
}
