package com.delfan.madp.favs;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class OfflineFavStore {
    private static final String PREF_NAME = "offline_favorites_v1";
    private static final String KEY_PAYLOAD = "payload";
    private static final Object LOCK = new Object();

    private static boolean loaded = false;
    private static final ArrayList<FavFolder> folders = new ArrayList<FavFolder>();
    private static final ArrayList<FavItem> items = new ArrayList<FavItem>();

    public static final class ImportResult {
        public int addedItems;
        public int updatedItems;
        public int addedFolders;
    }

    private OfflineFavStore() {}

    private static void ensureLoaded(Context ctx) {
        if (loaded || ctx == null) return;
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_PAYLOAD, "");
        folders.clear();
        items.clear();
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject root = new JSONObject(raw);
                JSONArray fArr = root.optJSONArray("folders");
                if (fArr != null) {
                    for (int i = 0; i < fArr.length(); i++) {
                        FavFolder f = FavFolder.fromJson(fArr.optJSONObject(i));
                        if (f != null && findFolderIndexById(f.id) < 0) {
                            folders.add(f);
                        }
                    }
                }
                JSONArray iArr = root.optJSONArray("favorites");
                if (iArr != null) {
                    for (int i = 0; i < iArr.length(); i++) {
                        FavItem it = FavItem.fromJson(iArr.optJSONObject(i));
                        if (it != null && findItemIndexById(it.videoid) < 0) {
                            items.add(it);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        loaded = true;
    }

    private static void persist(Context ctx) {
        if (ctx == null) return;
        JSONObject root = buildPayloadObject();
        Context app = ctx.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_PAYLOAD, root.toString()).commit();
    }

    private static JSONObject buildPayloadObject() {
        JSONObject root = new JSONObject();
        try {
            root.put("version", 1);
            root.put("exportedAt", System.currentTimeMillis());
            JSONArray fArr = new JSONArray();
            for (FavFolder f : folders) {
                fArr.put(f.toJson());
            }
            root.put("folders", fArr);
            JSONArray iArr = new JSONArray();
            for (FavItem it : items) {
                iArr.put(it.toJson());
            }
            root.put("favorites", iArr);
        } catch (Throwable ignored) {}
        return root;
    }

    private static int findFolderIndexById(String id) {
        if (id == null) return -1;
        for (int i = 0; i < folders.size(); i++) {
            if (id.equals(folders.get(i).id)) return i;
        }
        return -1;
    }

    private static int findFolderIndexByName(String name) {
        if (name == null) return -1;
        String t = name.trim();
        for (int i = 0; i < folders.size(); i++) {
            if (t.equalsIgnoreCase(folders.get(i).name.trim())) return i;
        }
        return -1;
    }

    private static int findItemIndexById(String videoid) {
        if (videoid == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (videoid.equals(items.get(i).videoid)) return i;
        }
        return -1;
    }

    public static boolean isFavorite(Context ctx, String videoid) {
        if (ctx == null || videoid == null || videoid.trim().isEmpty()) return false;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            return findItemIndexById(videoid.trim()) >= 0;
        }
    }

    public static FavItem getItem(Context ctx, String videoid) {
        if (ctx == null || videoid == null || videoid.trim().isEmpty()) return null;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int idx = findItemIndexById(videoid.trim());
            return idx >= 0 ? items.get(idx).copy() : null;
        }
    }

    public static void saveFavorite(Context ctx, FavItem input, List<String> selectedFolderIds) {
        if (ctx == null || input == null || input.videoid == null || input.videoid.trim().isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded(ctx);
            String id = input.videoid.trim();
            HashSet<String> validFolderIds = new HashSet<String>();
            for (FavFolder f : folders) {
                validFolderIds.add(f.id);
            }
            ArrayList<String> cleanFolders = new ArrayList<String>();
            if (selectedFolderIds != null) {
                for (String fid : selectedFolderIds) {
                    if (fid != null && validFolderIds.contains(fid) && !cleanFolders.contains(fid)) {
                        cleanFolders.add(fid);
                    }
                }
            }

            int idx = findItemIndexById(id);
            if (idx >= 0) {
                FavItem existing = items.get(idx);
                mergeMetadata(existing, input);
                existing.folderIds = cleanFolders;
            } else {
                FavItem fresh = input.copy();
                fresh.videoid = id;
                fresh.what = FavItem.normalizeWhat(fresh.what);
                if (fresh.addedAt <= 0L) fresh.addedAt = System.currentTimeMillis();
                fresh.folderIds = cleanFolders;
                items.add(0, fresh);
            }
            persist(ctx);
        }
    }

    public static void updateMetadataIfFavorited(Context ctx, FavItem input) {
        if (ctx == null || input == null || input.videoid == null || input.videoid.trim().isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int idx = findItemIndexById(input.videoid.trim());
            if (idx < 0) return;
            FavItem existing = items.get(idx);
            if (mergeMetadata(existing, input)) {
                persist(ctx);
            }
        }
    }

    private static boolean mergeMetadata(FavItem dst, FavItem src) {
        boolean changed = false;
        if (notEmpty(src.title) && !src.title.equals(dst.title)) {
            dst.title = src.title;
            changed = true;
        }
        if (notEmpty(src.des) && !src.des.equals(dst.des)) {
            dst.des = src.des;
            changed = true;
        }
        if (notEmpty(src.poster) && !src.poster.equals(dst.poster)) {
            dst.poster = src.poster;
            changed = true;
        }
        if (notEmpty(src.what)) {
            String nw = FavItem.normalizeWhat(src.what);
            if (!nw.equals(dst.what)) {
                dst.what = nw;
                changed = true;
            }
        }
        if (notEmpty(src.year) && !src.year.equals(dst.year)) {
            dst.year = src.year;
            changed = true;
        }
        if (notEmpty(src.imdb) && !src.imdb.equals(dst.imdb)) {
            dst.imdb = src.imdb;
            changed = true;
        }
        return changed;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public static void removeFavorite(Context ctx, String videoid) {
        if (ctx == null || videoid == null || videoid.trim().isEmpty()) return;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int idx = findItemIndexById(videoid.trim());
            if (idx >= 0) {
                items.remove(idx);
                persist(ctx);
            }
        }
    }

    public static ArrayList<FavItem> getAllFavorites(Context ctx) {
        ArrayList<FavItem> out = new ArrayList<FavItem>();
        if (ctx == null) return out;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            for (FavItem it : items) out.add(it.copy());
        }
        return out;
    }

    public static ArrayList<FavItem> getMovies(Context ctx) {
        ArrayList<FavItem> out = new ArrayList<FavItem>();
        if (ctx == null) return out;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            for (FavItem it : items) {
                if (it.isMovie()) out.add(it.copy());
            }
        }
        return out;
    }

    public static ArrayList<FavItem> getSeries(Context ctx) {
        ArrayList<FavItem> out = new ArrayList<FavItem>();
        if (ctx == null) return out;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            for (FavItem it : items) {
                if (it.isSeries()) out.add(it.copy());
            }
        }
        return out;
    }

    public static ArrayList<FavItem> getFolderItems(Context ctx, String folderId) {
        ArrayList<FavItem> out = new ArrayList<FavItem>();
        if (ctx == null || folderId == null || folderId.isEmpty()) return out;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            for (FavItem it : items) {
                if (it.folderIds.contains(folderId)) out.add(it.copy());
            }
        }
        return out;
    }

    public static int getFolderCount(Context ctx, String folderId) {
        if (ctx == null || folderId == null || folderId.isEmpty()) return 0;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int c = 0;
            for (FavItem it : items) {
                if (it.folderIds.contains(folderId)) c++;
            }
            return c;
        }
    }

    public static ArrayList<FavFolder> getFolders(Context ctx) {
        ArrayList<FavFolder> out = new ArrayList<FavFolder>();
        if (ctx == null) return out;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            for (FavFolder f : folders) out.add(f.copy());
        }
        return out;
    }

    public static FavFolder getFolder(Context ctx, String folderId) {
        if (ctx == null || folderId == null) return null;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int idx = findFolderIndexById(folderId);
            return idx >= 0 ? folders.get(idx).copy() : null;
        }
    }

    public static FavFolder createFolder(Context ctx, String rawName) {
        if (ctx == null || rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int existing = findFolderIndexByName(name);
            if (existing >= 0) {
                return folders.get(existing).copy();
            }
            FavFolder f = new FavFolder();
            f.id = "fld_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            f.name = name;
            f.createdAt = System.currentTimeMillis();
            folders.add(f);
            persist(ctx);
            return f.copy();
        }
    }

    public static boolean renameFolder(Context ctx, String folderId, String rawNewName) {
        if (ctx == null || folderId == null || rawNewName == null) return false;
        String newName = rawNewName.trim();
        if (newName.isEmpty()) return false;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int idx = findFolderIndexById(folderId);
            if (idx < 0) return false;
            int dup = findFolderIndexByName(newName);
            if (dup >= 0 && dup != idx) return false;
            folders.get(idx).name = newName;
            persist(ctx);
            return true;
        }
    }

    public static boolean deleteFolder(Context ctx, String folderId) {
        if (ctx == null || folderId == null) return false;
        synchronized (LOCK) {
            ensureLoaded(ctx);
            int idx = findFolderIndexById(folderId);
            if (idx < 0) return false;
            folders.remove(idx);
            for (FavItem it : items) {
                it.folderIds.remove(folderId);
            }
            persist(ctx);
            return true;
        }
    }

    public static String exportJson(Context ctx) {
        if (ctx == null) return "{}";
        synchronized (LOCK) {
            ensureLoaded(ctx);
            try {
                return buildPayloadObject().toString(2);
            } catch (Throwable t) {
                return buildPayloadObject().toString();
            }
        }
    }

    public static ImportResult importJson(Context ctx, String rawJson) throws Exception {
        if (ctx == null || rawJson == null || rawJson.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty JSON");
        }
        JSONObject root = new JSONObject(rawJson.trim());
        JSONArray fArr = root.optJSONArray("folders");
        JSONArray iArr = root.optJSONArray("favorites");
        if (fArr == null && iArr == null) {
            throw new IllegalArgumentException("Missing folders/favorites arrays");
        }

        ImportResult res = new ImportResult();
        synchronized (LOCK) {
            ensureLoaded(ctx);
            HashMap<String, String> folderIdMap = new HashMap<String, String>();
            if (fArr != null) {
                for (int i = 0; i < fArr.length(); i++) {
                    FavFolder incoming = FavFolder.fromJson(fArr.optJSONObject(i));
                    if (incoming == null) continue;
                    int byId = findFolderIndexById(incoming.id);
                    if (byId >= 0) {
                        folderIdMap.put(incoming.id, folders.get(byId).id);
                    } else {
                        int byName = findFolderIndexByName(incoming.name);
                        if (byName >= 0) {
                            folderIdMap.put(incoming.id, folders.get(byName).id);
                        } else {
                            folders.add(incoming);
                            folderIdMap.put(incoming.id, incoming.id);
                            res.addedFolders++;
                        }
                    }
                }
            }

            if (iArr != null) {
                for (int i = 0; i < iArr.length(); i++) {
                    FavItem incoming = FavItem.fromJson(iArr.optJSONObject(i));
                    if (incoming == null) continue;
                    ArrayList<String> mappedFolders = new ArrayList<String>();
                    for (String oldFid : incoming.folderIds) {
                        String newFid = folderIdMap.get(oldFid);
                        if (newFid == null && findFolderIndexById(oldFid) >= 0) {
                            newFid = oldFid;
                        }
                        if (newFid != null && !mappedFolders.contains(newFid)) {
                            mappedFolders.add(newFid);
                        }
                    }
                    int existingIdx = findItemIndexById(incoming.videoid);
                    if (existingIdx >= 0) {
                        FavItem existing = items.get(existingIdx);
                        mergeMetadata(existing, incoming);
                        for (String fid : mappedFolders) {
                            if (!existing.folderIds.contains(fid)) {
                                existing.folderIds.add(fid);
                            }
                        }
                        res.updatedItems++;
                    } else {
                        incoming.folderIds = mappedFolders;
                        items.add(incoming);
                        res.addedItems++;
                    }
                }
            }
            persist(ctx);
        }
        return res;
    }
}
