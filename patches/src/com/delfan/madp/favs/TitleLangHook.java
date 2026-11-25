package com.delfan.madp.favs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;
import com.delfan.madp.Acts.activity_splash;
import com.delfan.madp.Other.b;

public final class TitleLangHook {
    private static final String PREF_NAME = "MyPrefers";
    private static final String KEY_LANG = "offline_title_lang";
    private static final String KEY_LANG_SET = "offline_title_lang_set";

    private TitleLangHook() {}

    public static boolean isEnglish(String lang) {
        return lang != null && "EN".equalsIgnoreCase(lang.trim());
    }

    public static void applySavedIfGuest(Context ctx) {
        if (ctx == null) return;
        if ("T".equals(b.s)) return;
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            if (sp.getBoolean(KEY_LANG_SET, false)) {
                String saved = sp.getString(KEY_LANG, "");
                b.j = isEnglish(saved) ? "EN" : "";
            }
        } catch (Throwable ignored) {}
    }

    public static void toggleOffline(Activity act) {
        if (act == null) return;
        boolean toEnglish = !isEnglish(b.j);
        String next = toEnglish ? "EN" : "";
        try {
            SharedPreferences sp = act.getApplicationContext()
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_LANG, next)
                    .putBoolean(KEY_LANG_SET, true)
                    .commit();
        } catch (Throwable ignored) {}
        b.j = next;
        try {
            Toast.makeText(
                    act,
                    toEnglish ? "عناوین فیلم‌ها به انگلیسی تغییر یافت" : "عناوین فیلم‌ها به فارسی تغییر یافت",
                    Toast.LENGTH_SHORT
            ).show();
        } catch (Throwable ignored) {}
        try {
            Intent intent = new Intent(act, activity_splash.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            act.startActivity(intent);
            act.finish();
        } catch (Throwable ignored) {}
    }
}
