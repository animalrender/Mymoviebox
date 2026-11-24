package com.delfan.madp.favs;

import org.json.JSONObject;

public class FavFolder {
    public String id = "";
    public String name = "";
    public long createdAt = 0L;

    public FavFolder copy() {
        FavFolder f = new FavFolder();
        f.id = id;
        f.name = name;
        f.createdAt = createdAt;
        return f;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id == null ? "" : id);
            o.put("name", name == null ? "" : name);
            o.put("createdAt", createdAt);
        } catch (Throwable ignored) {}
        return o;
    }

    public static FavFolder fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "").trim();
        String name = o.optString("name", "").trim();
        if (id.isEmpty() || name.isEmpty()) return null;
        FavFolder f = new FavFolder();
        f.id = id;
        f.name = name;
        f.createdAt = o.optLong("createdAt", System.currentTimeMillis());
        return f;
    }
}
