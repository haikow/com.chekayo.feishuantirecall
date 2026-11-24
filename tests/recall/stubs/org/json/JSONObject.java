package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

public class JSONObject {
    private final Map<String, Object> map = new LinkedHashMap<String, Object>();

    public JSONObject() {}
    public JSONObject(String json) {}

    public JSONObject put(String name, Object value) {
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, boolean value) {
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, int value) {
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, long value) {
        map.put(name, value);
        return this;
    }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        return v instanceof String ? (String) v : fallback;
    }

    public int length() {
        return map.size();
    }

    public java.util.Iterator<String> keys() {
        return map.keySet().iterator();
    }

    public Object opt(String name) {
        return map.get(name);
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        return v instanceof JSONObject ? (JSONObject) v : null;
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public Object remove(String name) {
        return map.remove(name);
    }

    public String toString() {
        return "{}";
    }

    public String toString(int indentSpaces) {
        return "{}";
    }
}
