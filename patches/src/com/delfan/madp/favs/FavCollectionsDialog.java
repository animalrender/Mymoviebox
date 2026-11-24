package com.delfan.madp.favs;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;

public final class FavCollectionsDialog {
    public interface OnChangedListener {
        void onFavoritesChanged();
    }

    private FavCollectionsDialog() {}

    public static void show(final Activity act, final FavItem currentItem, final OnChangedListener listener) {
        if (act == null || currentItem == null || currentItem.videoid == null || currentItem.videoid.trim().isEmpty()) {
            return;
        }
        int layoutId = Res.layout(act, "dialog_fav_collections");
        if (layoutId == 0) return;

        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutId);
        dialog.setCancelable(true);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            int width = (int) (dm.widthPixels * 0.92f);
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        final TextView txtHeader = (TextView) dialog.findViewById(Res.id(act, "TxtHeader_FavDlg"));
        final TextView txtTitle = (TextView) dialog.findViewById(Res.id(act, "TxtTitle_FavDlg"));
        final TextView btnClose = (TextView) dialog.findViewById(Res.id(act, "BtnClose_FavDlg"));
        final LinearLayout containerFolders = (LinearLayout) dialog.findViewById(Res.id(act, "ContainerFolders_FavDlg"));
        final EditText editNewFolder = (EditText) dialog.findViewById(Res.id(act, "EditNewFolder_FavDlg"));
        final TextView btnCreateFolder = (TextView) dialog.findViewById(Res.id(act, "BtnCreateFolder_FavDlg"));
        final TextView btnRemove = (TextView) dialog.findViewById(Res.id(act, "BtnRemoveFav_FavDlg"));
        final TextView btnSave = (TextView) dialog.findViewById(Res.id(act, "BtnSaveFav_FavDlg"));

        final FavItem savedItem = OfflineFavStore.getItem(act, currentItem.videoid);
        final boolean alreadyFav = (savedItem != null);
        final HashSet<String> selectedFolderIds = new HashSet<String>();
        if (savedItem != null && savedItem.folderIds != null) {
            selectedFolderIds.addAll(savedItem.folderIds);
        }

        if (txtHeader != null) {
            txtHeader.setText(alreadyFav ? "مدیریت علاقه‌مندی و پوشه‌ها" : "ذخیره در علاقه‌مندی‌ها");
        }
        if (txtTitle != null) {
            String t = (currentItem.title != null && !currentItem.title.trim().isEmpty())
                    ? currentItem.title.trim()
                    : ("شناسه " + currentItem.videoid);
            txtTitle.setText(t);
        }
        if (btnRemove != null) {
            btnRemove.setVisibility(alreadyFav ? View.VISIBLE : View.GONE);
        }
        if (btnSave != null) {
            btnSave.setText(alreadyFav ? "به‌روزرسانی" : "ذخیره علاقه‌مندی");
        }

        final Runnable rebuildFolderRows = new Runnable() {
            @Override
            public void run() {
                buildFolderList(act, containerFolders, selectedFolderIds);
            }
        };
        rebuildFolderRows.run();

        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }

        if (btnCreateFolder != null && editNewFolder != null) {
            btnCreateFolder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String name = editNewFolder.getText() == null ? "" : editNewFolder.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(act, "لطفاً نام پوشه را وارد کنید", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FavFolder created = OfflineFavStore.createFolder(act, name);
                    if (created != null) {
                        selectedFolderIds.add(created.id);
                        editNewFolder.setText("");
                        hideKeyboard(act, editNewFolder);
                        rebuildFolderRows.run();
                        Toast.makeText(act, "پوشه «" + created.name + "» ایجاد و انتخاب شد", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ArrayList<String> list = new ArrayList<String>(selectedFolderIds);
                    OfflineFavStore.saveFavorite(act, currentItem, list);
                    Toast.makeText(
                            act,
                            alreadyFav ? "تغییرات علاقه‌مندی ذخیره شد" : "به لیست علاقه‌مندی‌های آفلاین اضافه شد",
                            Toast.LENGTH_SHORT
                    ).show();
                    dialog.dismiss();
                    if (listener != null) listener.onFavoritesChanged();
                }
            });
        }

        if (btnRemove != null) {
            btnRemove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    OfflineFavStore.removeFavorite(act, currentItem.videoid);
                    Toast.makeText(act, "از لیست علاقه‌مندی‌ها حذف شد", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    if (listener != null) listener.onFavoritesChanged();
                }
            });
        }

        dialog.show();
    }

    private static void hideKeyboard(Context ctx, View v) {
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && v != null) {
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    private static void buildFolderList(final Context ctx, final LinearLayout container, final HashSet<String> selectedIds) {
        if (container == null) return;
        container.removeAllViews();

        ArrayList<FavFolder> allFolders = OfflineFavStore.getFolders(ctx);
        Typeface tf = Res.sans(ctx);
        int textPrimary = Res.getColor(ctx, "TxtcolorHome", Res.isNightMode() ? Color.WHITE : Color.BLACK);
        int textSecondary = Res.getColor(ctx, "TxtcolorHome2", Res.isNightMode() ? 0xFFD1D0D0 : 0xFF636363);
        int accentColor = Res.getColor(ctx, "BtnPopLogin", 0xFF0277BD);
        int rowBg = Res.getColor(ctx, "BgAct", Res.isNightMode() ? 0xFF3C3C3C : Color.WHITE);
        float dp = ctx.getResources().getDisplayMetrics().density;

        if (allFolders.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("هنوز پوشه‌ای نساخته‌اید؛ می‌توانید بدون پوشه ذخیره کنید یا از کادر پایین پوشه بسازید.");
            empty.setTextColor(textSecondary);
            empty.setTextSize(12f);
            empty.setTypeface(tf);
            empty.setGravity(Gravity.RIGHT);
            empty.setPadding((int) (6 * dp), (int) (14 * dp), (int) (6 * dp), (int) (14 * dp));
            container.addView(empty);
            return;
        }

        for (final FavFolder folder : allFolders) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(rowBg);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = (int) (5 * dp);
            row.setLayoutParams(rlp);
            row.setPadding((int) (10 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
            row.setClickable(true);
            row.setFocusable(true);

            int count = OfflineFavStore.getFolderCount(ctx, folder.id);
            TextView tvCount = new TextView(ctx);
            tvCount.setText(Res.toPersianDigits(count) + " عنوان");
            tvCount.setTextColor(textSecondary);
            tvCount.setTextSize(11f);
            tvCount.setTypeface(tf);
            row.addView(tvCount);

            TextView tvName = new TextView(ctx);
            tvName.setText(folder.name);
            tvName.setTextColor(textPrimary);
            tvName.setTextSize(13f);
            tvName.setTypeface(tf, Typeface.BOLD);
            tvName.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = (int) (8 * dp);
            nlp.rightMargin = (int) (6 * dp);
            tvName.setLayoutParams(nlp);
            row.addView(tvName);

            final CheckBox cb = new CheckBox(ctx);
            cb.setChecked(selectedIds.contains(folder.id));
            if (Build.VERSION.SDK_INT >= 21) {
                cb.setButtonTintList(ColorStateList.valueOf(Res.isNightMode() ? Color.WHITE : accentColor));
            }
            cb.setClickable(false);
            cb.setFocusable(false);
            row.addView(cb);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !cb.isChecked();
                    cb.setChecked(next);
                    if (next) {
                        selectedIds.add(folder.id);
                    } else {
                        selectedIds.remove(folder.id);
                    }
                }
            });

            container.addView(row);
        }
    }
}
