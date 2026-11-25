package com.delfan.madp.favs;

import android.content.Context;
import android.graphics.Typeface;
import androidx.core.content.res.ResourcesCompat;
import com.delfan.madp.Other.b;
import java.util.HashMap;

public final class Res {
    private static final HashMap<String, Integer> CACHE = new HashMap<String, Integer>();

    private Res() {}

    public static int get(Context ctx, String type, String name) {
        if (ctx == null || name == null) return 0;
        String key = type + "/" + name;
        synchronized (CACHE) {
            Integer v = CACHE.get(key);
            if (v != null) return v;
        }
        int resId = ctx.getResources().getIdentifier(name, type, ctx.getPackageName());
        if (resId != 0) {
            synchronized (CACHE) {
                CACHE.put(key, resId);
            }
        }
        return resId;
    }

    public static int id(Context ctx, String name) { return get(ctx, "id", name); }
    public static int layout(Context ctx, String name) { return get(ctx, "layout", name); }
    public static int drawable(Context ctx, String name) { return get(ctx, "drawable", name); }
    public static int color(Context ctx, String name) { return get(ctx, "color", name); }
    public static int font(Context ctx, String name) { return get(ctx, "font", name); }

    public static int integer(Context ctx, String name, int fallback) {
        int id = get(ctx, "integer", name);
        if (id == 0) return fallback;
        try {
            return ctx.getResources().getInteger(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static int getColor(Context ctx, String name, int fallback) {
        int id = color(ctx, name);
        if (id == 0) return fallback;
        try {
            return ctx.getResources().getColor(id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static Typeface sans(Context ctx) {
        int f = font(ctx, "sans");
        if (f != 0) {
            try {
                Typeface tf = ResourcesCompat.getFont(ctx, f);
                if (tf != null) return tf;
            } catch (Throwable ignored) {}
        }
        return Typeface.DEFAULT;
    }

    public static boolean isNightMode() {
        return "T".equals(b.r);
    }

    public static String toPersianDigits(int n) {
        String s = String.valueOf(n);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append((char) ('\u06F0' + (c - '0')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String toAsciiDigits(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '\u06F0' && c <= '\u06F9') {
                sb.append((char) ('0' + (c - '\u06F0')));
            } else if (c >= '\u0660' && c <= '\u0669') {
                sb.append((char) ('0' + (c - '\u0660')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
