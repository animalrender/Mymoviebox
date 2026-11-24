package com.delfan.madp.favs;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.delfan.madp.fragments.fr_favs;

public final class FavHook {
    private FavHook() {}

    public static String onDetailsCreate(Activity act, String videoid, String title, String poster, String what) {
        if (act == null) return "F";
        FavItem saved = OfflineFavStore.getItem(act, videoid);
        boolean fav = (saved != null);
        if (saved != null) {
            prefillTextIfEmpty(act, "TxtDes_ActDetialsVideo", saved.des);
            prefillTextIfEmpty(act, "TxtYear_ActDetialsVideo", saved.year);
            if (saved.imdb != null && !saved.imdb.trim().isEmpty()) {
                prefillTextIfEmpty(act, "TxtImdb_ActDetialsVideo", "IMDB : " + saved.imdb.trim());
            }
        }
        applyButtonState(act, videoid, fav);
        return fav ? "T" : "F";
    }

    private static void prefillTextIfEmpty(Activity act, String idName, String value) {
        if (value == null || value.trim().isEmpty()) return;
        try {
            View v = act.findViewById(Res.id(act, idName));
            if (v instanceof TextView) {
                CharSequence cur = ((TextView) v).getText();
                if (cur == null || cur.toString().trim().isEmpty()) {
                    ((TextView) v).setText(value.trim());
                }
            }
        } catch (Throwable ignored) {}
    }

    public static String onDetailsLoaded(Activity act, String videoid, String title, String poster, String what) {
        if (act == null) return "F";
        FavItem snapshot = captureDetailsSnapshot(act, videoid, title, poster, what);
        OfflineFavStore.updateMetadataIfFavorited(act, snapshot);
        boolean fav = OfflineFavStore.isFavorite(act, videoid);
        applyButtonState(act, videoid, fav);
        return fav ? "T" : "F";
    }

    public static void onDetailsFavClicked(final Activity act, final String videoid, String title, String poster, String what) {
        if (act == null) return;
        FavItem snapshot = captureDetailsSnapshot(act, videoid, title, poster, what);
        FavCollectionsDialog.show(act, snapshot, new FavCollectionsDialog.OnChangedListener() {
            @Override
            public void onFavoritesChanged() {
                boolean fav = OfflineFavStore.isFavorite(act, videoid);
                applyButtonState(act, videoid, fav);
                OfflineFavsController.notifyFavoritesChanged(act);
                try {
                    fr_favs.EmptyList_Frg5();
                    fr_favs.SetDataFavs(act);
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void applyButtonState(Activity act, String videoid, boolean isFav) {
        try {
            View prog = act.findViewById(Res.id(act, "ProgBtn3"));
            if (prog instanceof ProgressBar || prog != null) {
                prog.setVisibility(View.GONE);
            }
            View btn = act.findViewById(Res.id(act, "BtnAddFave_ADV"));
            if (btn != null) {
                btn.setVisibility(View.VISIBLE);
                btn.setClickable(true);
            }
            View iconView = act.findViewById(Res.id(act, "IconBtnFave_ADV"));
            if (iconView instanceof ImageView) {
                String iconName;
                if (isFav) {
                    iconName = Res.isNightMode() ? "ic_star_full_w" : "ic_star_full";
                } else {
                    iconName = Res.isNightMode() ? "ic_star_w" : "ic_star";
                }
                int resId = Res.drawable(act, iconName);
                if (resId != 0) {
                    ((ImageView) iconView).setImageResource(resId);
                }
            }
            View txtView = act.findViewById(Res.id(act, "TxtBtnFave_ADV"));
            if (txtView instanceof TextView) {
                if (isFav) {
                    FavItem item = OfflineFavStore.getItem(act, videoid);
                    int nFolders = (item != null && item.folderIds != null) ? item.folderIds.size() : 0;
                    if (nFolders > 0) {
                        ((TextView) txtView).setText("در علاقه‌مندی‌ها (" + Res.toPersianDigits(nFolders) + ")");
                    } else {
                        ((TextView) txtView).setText("در علاقه‌مندی‌ها");
                    }
                } else {
                    ((TextView) txtView).setText("افزودن به علاقه‌مندی");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static FavItem captureDetailsSnapshot(Activity act, String videoid, String title, String poster, String what) {
        FavItem item = new FavItem();
        Intent intent = act.getIntent();

        item.videoid = firstNonEmpty(videoid, intent != null ? intent.getStringExtra("videoid") : "");
        item.poster = firstNonEmpty(poster, intent != null ? intent.getStringExtra("poster") : "");
        item.what = FavItem.normalizeWhat(firstNonEmpty(what, intent != null ? intent.getStringExtra("what") : ""));

        String viewTitle = readTextView(act, "TxtTitle_ActDetialsVideo");
        item.title = firstNonEmpty(viewTitle, firstNonEmpty(title, intent != null ? intent.getStringExtra("title") : ""));

        String viewDes = readTextView(act, "TxtDes_ActDetialsVideo");
        item.des = firstNonEmpty(viewDes, intent != null ? intent.getStringExtra("des") : "");

        String viewYear = cleanPrefix(readTextView(act, "TxtYear_ActDetialsVideo"), "سال");
        item.year = viewYear;

        String viewImdb = cleanImdb(readTextView(act, "TxtImdb_ActDetialsVideo"));
        item.imdb = viewImdb;

        return item;
    }

    private static String readTextView(Activity act, String idName) {
        try {
            View v = act.findViewById(Res.id(act, idName));
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null) return cs.toString().trim();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String cleanImdb(String raw) {
        if (raw == null) return "";
        String s = raw.replace("IMDB", "").replace("imdb", "").replace(":", "").trim();
        return s;
    }

    private static String cleanPrefix(String raw, String prefixWord) {
        if (raw == null) return "";
        String s = raw.replace(prefixWord, "").replace(":", "").trim();
        return s;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        if (b != null && !b.trim().isEmpty()) return b.trim();
        return "";
    }
}
