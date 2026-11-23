package com.anand.vault;

import org.json.JSONObject;

/** Passes updates from background work to the open screen (if any). */
final class Events {
    interface Sink {
        void emit(String name, JSONObject data);
    }

    private static volatile Sink sink;

    private Events() {}

    static void set(Sink s) {
        sink = s;
    }

    static void clear(Sink s) {
        if (sink == s) sink = null;
    }

    static void emit(String name, JSONObject data) {
        Sink s = sink;
        if (s != null) s.emit(name, data == null ? new JSONObject() : data);
    }

    /** Builds a JSON object from key/value pairs. Null values are left out. */
    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                if (kv[i + 1] != null) o.put((String) kv[i], kv[i + 1]);
            } catch (Exception ignored) {
            }
        }
        return o;
    }
}
