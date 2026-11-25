package com.delfan.madp.favs;

import i.d;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class FavItem {
    public String videoid = "";
    public String title = "";
    public String des = "";
    public String poster = "";
    public String what = "movie";
    public String year = "";
    public String imdb = "";
    public long addedAt = 0L;
    public ArrayList<String> folderIds = new ArrayList<String>();

    public static String normalizeWhat(String raw) {
        if (raw == null) return "movie";
        String w = raw.trim().toLowerCase();
        if (w.equals("seryal") || w.equals("serie") || w.equals("series")
                || w.equals("tvseries") || w.equals("0") || w.contains("ser")) {
            return "seryal";
        }
        return "movie";
    }

    public boolean isSeries() {
        return "seryal".equals(normalizeWhat(what));
    }

    public boolean isMovie() {
        return !isSeries();
    }

    public FavItem copy() {
        FavItem c = new FavItem();
        c.videoid = videoid;
        c.title = title;
        c.des = des;
        c.poster = poster;
        c.what = what;
        c.year = year;
        c.imdb = imdb;
        c.addedAt = addedAt;
        c.folderIds = new ArrayList<String>(folderIds);
        return c;
    }

    public d toLegacyLiD() {
        return new d(
                safe(videoid),
                safe(title),
                safe(des),
                "",
                safe(poster),
                "",
                normalizeWhat(what),
                safe(year),
                Res.toAsciiDigits(safe(imdb))
        );
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("videoid", safe(videoid));
            o.put("title", safe(title));
            o.put("des", safe(des));
            o.put("poster", safe(poster));
            o.put("what", normalizeWhat(what));
            o.put("year", safe(year));
            o.put("imdb", safe(imdb));
            o.put("addedAt", addedAt);
            JSONArray arr = new JSONArray();
            for (String fid : folderIds) {
                if (fid != null && !fid.isEmpty()) arr.put(fid);
            }
            o.put("folderIds", arr);
        } catch (Throwable ignored) {}
        return o;
    }

    public static FavItem fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("videoid", "").trim();
        if (id.isEmpty()) return null;
        FavItem item = new FavItem();
        item.videoid = id;
        item.title = o.optString("title", "");
        item.des = o.optString("des", "");
        item.poster = o.optString("poster", "");
        item.what = normalizeWhat(o.optString("what", "movie"));
        item.year = o.optString("year", "");
        item.imdb = Res.toAsciiDigits(o.optString("imdb", ""));
        item.addedAt = o.optLong("addedAt", System.currentTimeMillis());
        JSONArray arr = o.optJSONArray("folderIds");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String fid = arr.optString(i, "").trim();
                if (!fid.isEmpty() && !item.folderIds.contains(fid)) {
                    item.folderIds.add(fid);
                }
            }
        }
        return item;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
