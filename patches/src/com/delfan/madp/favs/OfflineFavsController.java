package com.delfan.madp.favs;

import i.d;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.delfan.madp.Acts.activity_main;
import com.delfan.madp.Adapter.CaAdFilm_DubleList;
import com.delfan.madp.Other.b;
import com.delfan.madp.Other.g;
import com.delfan.madp.fragments.fr_favs;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public final class OfflineFavsController {
    private static final int TAB_ALL = 0;
    private static final int TAB_MOVIES = 1;
    private static final int TAB_SERIES = 2;
    private static final int TAB_FOLDERS = 3;

    private static final int REQ_EXPORT = 9501;
    private static final int REQ_IMPORT = 9502;

    private static WeakReference<Fragment> activeFragRef = new WeakReference<Fragment>(null);
    private static WeakReference<View> rootRef = new WeakReference<View>(null);

    private static int currentTab = TAB_ALL;
    private static String openFolderId = null;

    private static final ArrayList<d> listAll = new ArrayList<d>();
    private static final ArrayList<d> listFolderItems = new ArrayList<d>();
    private static CaAdFilm_DubleList adapterAll;
    private static CaAdFilm_DubleList adapterFolderItems;
    private static FolderAdapter adapterFolders;

    private OfflineFavsController() {}

    public static void onCreateView(final Fragment frag, View root) {
        if (frag == null || root == null) return;
        activeFragRef = new WeakReference<Fragment>(frag);
        rootRef = new WeakReference<View>(root);

        final Context ctx = frag.getContext();
        if (ctx == null) return;

        if (fr_favs.list_cinema == null) fr_favs.list_cinema = new ArrayList();
        if (fr_favs.list_seryal == null) fr_favs.list_seryal = new ArrayList();

        fr_favs.FrLayoutLogin_Frg5 = (FrameLayout) root.findViewById(Res.id(ctx, "FrLayoutLogin_Frg5"));
        if (fr_favs.FrLayoutLogin_Frg5 != null) {
            fr_favs.FrLayoutLogin_Frg5.setVisibility(View.GONE);
        }

        fr_favs.Swipe1_Fr5 = (SwipeRefreshLayout) root.findViewById(Res.id(ctx, "Swipe1_Fr5"));
        fr_favs.Swipe2_Fr5 = (SwipeRefreshLayout) root.findViewById(Res.id(ctx, "Swipe2_Fr5"));
        fr_favs.RecyclerCinema_Frg5 = (RecyclerView) root.findViewById(Res.id(ctx, "RecyclerCinema_Frg5"));
        fr_favs.RecyclerSeryal_Frg5 = (RecyclerView) root.findViewById(Res.id(ctx, "RecyclerSeryal_Frg5"));
        fr_favs.TxtMsg1_Fr5 = (TextView) root.findViewById(Res.id(ctx, "TxtMsg1_Fr5"));
        fr_favs.TxtMsg2_Fr5 = (TextView) root.findViewById(Res.id(ctx, "TxtMsg2_Fr5"));

        final RecyclerView rvAll = (RecyclerView) root.findViewById(Res.id(ctx, "RecyclerAll_Frg5"));
        final SwipeRefreshLayout swipeAll = (SwipeRefreshLayout) root.findViewById(Res.id(ctx, "SwipeAll_Fr5"));
        final RecyclerView rvFolders = (RecyclerView) root.findViewById(Res.id(ctx, "RecyclerFolders_Frg5"));
        final RecyclerView rvFolderItems = (RecyclerView) root.findViewById(Res.id(ctx, "RecyclerFolderItems_Frg5"));

        int span = b.q > 1 ? b.q : 3;

        if (rvAll != null) {
            rvAll.setLayoutManager(new GridLayoutManager(ctx, span));
            adapterAll = new CaAdFilm_DubleList(listAll, ctx, rvAll);
            rvAll.setAdapter(adapterAll);
        }
        if (fr_favs.RecyclerCinema_Frg5 != null) {
            fr_favs.RecyclerCinema_Frg5.setLayoutManager(new GridLayoutManager(ctx, span));
            fr_favs.adapter_cinema = new CaAdFilm_DubleList(fr_favs.list_cinema, ctx, fr_favs.RecyclerCinema_Frg5);
            fr_favs.RecyclerCinema_Frg5.setAdapter(fr_favs.adapter_cinema);
        }
        if (fr_favs.RecyclerSeryal_Frg5 != null) {
            fr_favs.RecyclerSeryal_Frg5.setLayoutManager(new GridLayoutManager(ctx, span));
            fr_favs.adapter_seryal = new CaAdFilm_DubleList(fr_favs.list_seryal, ctx, fr_favs.RecyclerSeryal_Frg5);
            fr_favs.RecyclerSeryal_Frg5.setAdapter(fr_favs.adapter_seryal);
        }
        if (rvFolderItems != null) {
            rvFolderItems.setLayoutManager(new GridLayoutManager(ctx, span));
            adapterFolderItems = new CaAdFilm_DubleList(listFolderItems, ctx, rvFolderItems);
            rvFolderItems.setAdapter(adapterFolderItems);
        }
        if (rvFolders != null) {
            rvFolders.setLayoutManager(new LinearLayoutManager(ctx));
            adapterFolders = new FolderAdapter(ctx, new FolderAdapter.Listener() {
                @Override
                public void onFolderClick(FavFolder folder) {
                    openFolderId = folder.id;
                    selectTab(ctx, TAB_FOLDERS);
                    reloadAll(ctx);
                }

                @Override
                public void onFolderEdit(FavFolder folder) {
                    showRenameFolderDialog(ctx, folder);
                }

                @Override
                public void onFolderDelete(FavFolder folder) {
                    showDeleteFolderDialog(ctx, folder);
                }
            });
            rvFolders.setAdapter(adapterFolders);
        }

        SwipeRefreshLayout.OnRefreshListener refreshListener = new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                reloadAll(ctx);
            }
        };
        if (swipeAll != null) swipeAll.setOnRefreshListener(refreshListener);
        if (fr_favs.Swipe1_Fr5 != null) fr_favs.Swipe1_Fr5.setOnRefreshListener(refreshListener);
        if (fr_favs.Swipe2_Fr5 != null) fr_favs.Swipe2_Fr5.setOnRefreshListener(refreshListener);

        View menuBtn = root.findViewById(Res.id(ctx, "RelIcMenu_ToolBar_Frg5"));
        if (menuBtn != null) {
            g.m(ctx, menuBtn, 0);
            menuBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        activity_main.OpenDrMenu();
                    } catch (Throwable ignored) {}
                }
            });
        }

        int tabBgColor = Res.getColor(ctx, "BgTabTextView", Res.isNightMode() ? 0xFF242323 : Color.WHITE);
        setupTab(ctx, root, "TabAll_Frg5", "TabAllTxt_Frg5", tabBgColor, TAB_ALL);
        setupTab(ctx, root, "TabFaraB_Frg5", "TabFaraBTxt_Frg5", tabBgColor, TAB_MOVIES);
        setupTab(ctx, root, "TabB_Frg5", "TabBTxt_Frg5", tabBgColor, TAB_SERIES);
        setupTab(ctx, root, "TabFolders_Frg5", "TabFoldersTxt_Frg5", tabBgColor, TAB_FOLDERS);

        int btnBlueColor = Res.getColor(ctx, "BtnPopLogin", 0xFF0277BD);
        View btnNewFolder = root.findViewById(Res.id(ctx, "BtnNewFolder_Frg5"));
        if (btnNewFolder != null) {
            g.m(ctx, btnNewFolder, btnBlueColor);
            btnNewFolder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCreateFolderDialog(ctx);
                }
            });
        }

        View btnBackFolder = root.findViewById(Res.id(ctx, "BtnBackFolder_Frg5"));
        if (btnBackFolder != null) {
            g.m(ctx, btnBackFolder, btnBlueColor);
            btnBackFolder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openFolderId = null;
                    reloadAll(ctx);
                }
            });
        }

        ImageView btnRenameFolder = (ImageView) root.findViewById(Res.id(ctx, "BtnRenameFolder_Frg5"));
        if (btnRenameFolder != null) {
            btnRenameFolder.setColorFilter(
                    Res.getColor(ctx, "TxtcolorHome2", Res.isNightMode() ? 0xFFD1D0D0 : 0xFF636363),
                    PorterDuff.Mode.SRC_IN
            );
            btnRenameFolder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (openFolderId != null) {
                        FavFolder f = OfflineFavStore.getFolder(ctx, openFolderId);
                        if (f != null) showRenameFolderDialog(ctx, f);
                    }
                }
            });
        }

        ImageView btnDeleteFolder = (ImageView) root.findViewById(Res.id(ctx, "BtnDeleteFolder_Frg5"));
        if (btnDeleteFolder != null) {
            btnDeleteFolder.setColorFilter(
                    Res.getColor(ctx, "BtnPopLoginNo", 0xFFEF574B),
                    PorterDuff.Mode.SRC_IN
            );
            btnDeleteFolder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (openFolderId != null) {
                        FavFolder f = OfflineFavStore.getFolder(ctx, openFolderId);
                        if (f != null) showDeleteFolderDialog(ctx, f);
                    }
                }
            });
        }

        int pillBgId = Res.drawable(ctx, "fav_toolbar_btn_bg");
        View btnExport = root.findViewById(Res.id(ctx, "BtnExport_Frg5"));
        if (btnExport != null) {
            if (pillBgId != 0) {
                try { g.p(ctx, btnExport, ctx.getResources().getDrawable(pillBgId)); } catch (Throwable ignored) {}
            }
            btnExport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchExport(frag, ctx);
                }
            });
        }

        View btnImport = root.findViewById(Res.id(ctx, "BtnImport_Frg5"));
        if (btnImport != null) {
            if (pillBgId != 0) {
                try { g.p(ctx, btnImport, ctx.getResources().getDrawable(pillBgId)); } catch (Throwable ignored) {}
            }
            btnImport.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchImport(frag, ctx);
                }
            });
        }

        selectTab(ctx, currentTab);
        reloadAll(ctx);
    }

    private static void setupTab(final Context ctx, View root, String tabId, String txtId, int tabBgColor, final int tabIndex) {
        View tab = root.findViewById(Res.id(ctx, tabId));
        View txt = root.findViewById(Res.id(ctx, txtId));
        if (tab != null) {
            if (txt != null) g.o(ctx, tab, txt, tabBgColor);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tabIndex == TAB_FOLDERS && currentTab == TAB_FOLDERS && openFolderId != null) {
                        openFolderId = null;
                    }
                    selectTab(ctx, tabIndex);
                    reloadAll(ctx);
                }
            });
        }
    }

    private static void selectTab(Context ctx, int tabIndex) {
        currentTab = tabIndex;
        View root = rootRef.get();
        if (root == null || ctx == null) return;

        int activeColor = Res.getColor(ctx, "BgTab", Res.isNightMode() ? Color.WHITE : 0xFF01579B);
        int inactiveColor = Res.getColor(ctx, "BgTabTextView", Res.isNightMode() ? 0xFF242323 : Color.WHITE);

        setViewBgColor(root, ctx, "TabAll_Frg5", tabIndex == TAB_ALL ? activeColor : inactiveColor);
        setViewBgColor(root, ctx, "TabFaraB_Frg5", tabIndex == TAB_MOVIES ? activeColor : inactiveColor);
        setViewBgColor(root, ctx, "TabB_Frg5", tabIndex == TAB_SERIES ? activeColor : inactiveColor);
        setViewBgColor(root, ctx, "TabFolders_Frg5", tabIndex == TAB_FOLDERS ? activeColor : inactiveColor);

        setVis(root, ctx, "SwipeAll_Fr5", tabIndex == TAB_ALL ? View.VISIBLE : View.GONE);
        setVis(root, ctx, "Swipe1_Fr5", tabIndex == TAB_MOVIES ? View.VISIBLE : View.GONE);
        setVis(root, ctx, "Swipe2_Fr5", tabIndex == TAB_SERIES ? View.VISIBLE : View.GONE);
        setVis(root, ctx, "LayoutFolders_Frg5", tabIndex == TAB_FOLDERS ? View.VISIBLE : View.GONE);
    }

    public static void onResume(Fragment frag) {
        if (frag == null) return;
        activeFragRef = new WeakReference<Fragment>(frag);
        Context ctx = frag.getContext();
        if (ctx != null) reloadAll(ctx);
    }

    public static void onHiddenChanged(Fragment frag, boolean hidden) {
        if (frag == null || hidden) return;
        activeFragRef = new WeakReference<Fragment>(frag);
        Context ctx = frag.getContext();
        if (ctx != null) reloadAll(ctx);
    }

    public static void refreshData(Context ctx) {
        if (ctx != null) reloadAll(ctx);
    }

    public static void notifyFavoritesChanged(Context ctx) {
        if (ctx != null) reloadAll(ctx);
    }

    public static void clearData() {
        listAll.clear();
        listFolderItems.clear();
        if (fr_favs.list_cinema != null) fr_favs.list_cinema.clear();
        if (fr_favs.list_seryal != null) fr_favs.list_seryal.clear();
        try {
            if (adapterAll != null) adapterAll.notifyDataSetChanged();
            if (adapterFolderItems != null) adapterFolderItems.notifyDataSetChanged();
            if (fr_favs.adapter_cinema != null) fr_favs.adapter_cinema.notifyDataSetChanged();
            if (fr_favs.adapter_seryal != null) fr_favs.adapter_seryal.notifyDataSetChanged();
        } catch (Throwable ignored) {}
    }

    public static void reloadAll(Context ctx) {
        if (ctx == null) return;
        View root = rootRef.get();

        ArrayList<FavItem> all = OfflineFavStore.getAllFavorites(ctx);
        ArrayList<FavItem> movies = OfflineFavStore.getMovies(ctx);
        ArrayList<FavItem> series = OfflineFavStore.getSeries(ctx);
        ArrayList<FavFolder> folders = OfflineFavStore.getFolders(ctx);

        listAll.clear();
        for (FavItem it : all) listAll.add(it.toLegacyLiD());

        if (fr_favs.list_cinema == null) fr_favs.list_cinema = new ArrayList();
        fr_favs.list_cinema.clear();
        for (FavItem it : movies) fr_favs.list_cinema.add(it.toLegacyLiD());

        if (fr_favs.list_seryal == null) fr_favs.list_seryal = new ArrayList();
        fr_favs.list_seryal.clear();
        for (FavItem it : series) fr_favs.list_seryal.add(it.toLegacyLiD());

        if (adapterAll != null) adapterAll.notifyDataSetChanged();
        if (fr_favs.adapter_cinema != null) fr_favs.adapter_cinema.notifyDataSetChanged();
        if (fr_favs.adapter_seryal != null) fr_favs.adapter_seryal.notifyDataSetChanged();
        if (adapterFolders != null) adapterFolders.setFolders(folders);

        if (root != null) {
            setVis(root, ctx, "TxtMsgAll_Fr5", all.isEmpty() ? View.VISIBLE : View.GONE);
            setVis(root, ctx, "TxtMsg1_Fr5", movies.isEmpty() ? View.VISIBLE : View.GONE);
            setVis(root, ctx, "TxtMsg2_Fr5", series.isEmpty() ? View.VISIBLE : View.GONE);
            setVis(root, ctx, "TxtMsgFolders_Fr5", folders.isEmpty() ? View.VISIBLE : View.GONE);

            SwipeRefreshLayout swAll = (SwipeRefreshLayout) root.findViewById(Res.id(ctx, "SwipeAll_Fr5"));
            if (swAll != null) swAll.setRefreshing(false);
            if (fr_favs.Swipe1_Fr5 != null) fr_favs.Swipe1_Fr5.setRefreshing(false);
            if (fr_favs.Swipe2_Fr5 != null) fr_favs.Swipe2_Fr5.setRefreshing(false);
            if (fr_favs.FrLayoutLogin_Frg5 != null) fr_favs.FrLayoutLogin_Frg5.setVisibility(View.GONE);

            FavFolder openFolder = (openFolderId != null) ? OfflineFavStore.getFolder(ctx, openFolderId) : null;
            if (openFolder == null) {
                openFolderId = null;
                setVis(root, ctx, "PanelFoldersList_Frg5", View.VISIBLE);
                setVis(root, ctx, "PanelFolderDetail_Frg5", View.GONE);
            } else {
                setVis(root, ctx, "PanelFoldersList_Frg5", View.GONE);
                setVis(root, ctx, "PanelFolderDetail_Frg5", View.VISIBLE);

                ArrayList<FavItem> fItems = OfflineFavStore.getFolderItems(ctx, openFolder.id);
                listFolderItems.clear();
                for (FavItem it : fItems) listFolderItems.add(it.toLegacyLiD());
                if (adapterFolderItems != null) adapterFolderItems.notifyDataSetChanged();

                TextView hdr = (TextView) root.findViewById(Res.id(ctx, "TxtFolderHeader_Frg5"));
                if (hdr != null) {
                    hdr.setText(openFolder.name + " (" + Res.toPersianDigits(fItems.size()) + ")");
                }
                setVis(root, ctx, "TxtMsgFolderItems_Fr5", fItems.isEmpty() ? View.VISIBLE : View.GONE);
            }
        }
    }

    private static void launchExport(Fragment frag, Context ctx) {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "mymoviebox-favorites.json");
            frag.startActivityForResult(intent, REQ_EXPORT);
        } catch (Throwable t) {
            Toast.makeText(ctx, "امکان باز کردن انتخابگر فایل وجود ندارد", Toast.LENGTH_SHORT).show();
        }
    }

    private static void launchImport(Fragment frag, Context ctx) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            if (Build.VERSION.SDK_INT >= 19) {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            }
            frag.startActivityForResult(intent, REQ_IMPORT);
        } catch (Throwable t) {
            Toast.makeText(ctx, "امکان باز کردن انتخابگر فایل وجود ندارد", Toast.LENGTH_SHORT).show();
        }
    }

    public static void onActivityResult(Fragment frag, int requestCode, int resultCode, Intent data) {
        if (frag == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Context ctx = frag.getContext();
        if (ctx == null) return;
        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT) {
            OutputStream os = null;
            try {
                String json = OfflineFavStore.exportJson(ctx);
                os = ctx.getContentResolver().openOutputStream(uri, "wt");
                if (os == null) os = ctx.getContentResolver().openOutputStream(uri);
                if (os != null) {
                    os.write(json.getBytes("UTF-8"));
                    os.flush();
                    Toast.makeText(ctx, "خروجی علاقه‌مندی‌ها ذخیره شد", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(ctx, "خطا در نوشتن فایل خروجی", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Toast.makeText(ctx, "خطا در ذخیره فایل خروجی", Toast.LENGTH_SHORT).show();
            } finally {
                if (os != null) {
                    try { os.close(); } catch (Throwable ignored) {}
                }
            }
        } else if (requestCode == REQ_IMPORT) {
            InputStream is = null;
            try {
                is = ctx.getContentResolver().openInputStream(uri);
                if (is == null) {
                    Toast.makeText(ctx, "امکان خواندن فایل انتخاب‌شده وجود ندارد", Toast.LENGTH_SHORT).show();
                    return;
                }
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                OfflineFavStore.ImportResult res = OfflineFavStore.importJson(ctx, sb.toString());
                reloadAll(ctx);
                int totalItems = res.addedItems + res.updatedItems;
                Toast.makeText(
                        ctx,
                        "بازیابی انجام شد: " + Res.toPersianDigits(totalItems) + " عنوان و " + Res.toPersianDigits(res.addedFolders) + " پوشه جدید",
                        Toast.LENGTH_LONG
                ).show();
            } catch (Throwable t) {
                Toast.makeText(ctx, "فایل انتخاب‌شده معتبر نیست", Toast.LENGTH_SHORT).show();
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static void showCreateFolderDialog(final Context ctx) {
        showTextInputDialog(ctx, "پوشه جدید", "نام پوشه را وارد کنید (مثلاً: آخر هفته)", "", "ایجاد", new TextInputAction() {
            @Override
            public void onSubmit(String text) {
                FavFolder f = OfflineFavStore.createFolder(ctx, text);
                if (f != null) {
                    reloadAll(ctx);
                    Toast.makeText(ctx, "پوشه «" + f.name + "» ایجاد شد", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private static void showRenameFolderDialog(final Context ctx, final FavFolder folder) {
        showTextInputDialog(ctx, "تغییر نام پوشه", "نام جدید پوشه", folder.name, "ذخیره", new TextInputAction() {
            @Override
            public void onSubmit(String text) {
                if (OfflineFavStore.renameFolder(ctx, folder.id, text)) {
                    reloadAll(ctx);
                    Toast.makeText(ctx, "نام پوشه تغییر یافت", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx, "این نام تکراری یا نامعتبر است", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private static void showDeleteFolderDialog(final Context ctx, final FavFolder folder) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Typeface tf = Res.sans(ctx);
        float dp = ctx.getResources().getDisplayMetrics().density;
        int textPrimary = Res.getColor(ctx, "TxtcolorHome", Res.isNightMode() ? Color.WHITE : Color.BLACK);
        int textSecondary = Res.getColor(ctx, "TxtcolorHome2", Res.isNightMode() ? 0xFFD1D0D0 : 0xFF636363);
        int btnCancelBg = Res.getColor(ctx, "BtnPopLogin", 0xFF0277BD);
        int btnDeleteBg = Res.getColor(ctx, "BtnPopLoginNo", 0xFFEF574B);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int radiusId = Res.drawable(ctx, "radius");
        if (radiusId != 0) box.setBackgroundResource(radiusId);
        else box.setBackgroundColor(Res.isNightMode() ? 0xFF181818 : Color.WHITE);
        box.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (14 * dp));

        TextView title = new TextView(ctx);
        title.setText("حذف پوشه");
        title.setTypeface(tf, Typeface.BOLD);
        title.setTextSize(15f);
        title.setTextColor(textPrimary);
        title.setGravity(Gravity.RIGHT);
        box.addView(title);

        TextView msg = new TextView(ctx);
        msg.setText("آیا پوشه «" + folder.name + "» حذف شود؟ فیلم‌ها و سریال‌های داخل لیست علاقه‌مندی باقی می‌مانند.");
        msg.setTypeface(tf);
        msg.setTextSize(13f);
        msg.setTextColor(textSecondary);
        msg.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = (int) (8 * dp);
        mlp.bottomMargin = (int) (14 * dp);
        msg.setLayoutParams(mlp);
        box.addView(msg);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("انصراف");
        cancelBtn.setTypeface(tf, Typeface.BOLD);
        cancelBtn.setTextSize(13f);
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setBackgroundColor(btnCancelBg);
        cancelBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, (int) (40 * dp), 1f);
        clp.rightMargin = (int) (6 * dp);
        cancelBtn.setLayoutParams(clp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancelBtn);

        TextView delBtn = new TextView(ctx);
        delBtn.setText("حذف پوشه");
        delBtn.setTypeface(tf, Typeface.BOLD);
        delBtn.setTextSize(13f);
        delBtn.setTextColor(Color.WHITE);
        delBtn.setBackgroundColor(btnDeleteBg);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setLayoutParams(new LinearLayout.LayoutParams(0, (int) (40 * dp), 1f));
        delBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OfflineFavStore.deleteFolder(ctx, folder.id);
                if (folder.id.equals(openFolderId)) openFolderId = null;
                dlg.dismiss();
                reloadAll(ctx);
                Toast.makeText(ctx, "پوشه حذف شد", Toast.LENGTH_SHORT).show();
            }
        });
        btns.addView(delBtn);

        box.addView(btns);
        dlg.setContentView(box);
        configureDialogWindow(dlg, ctx);
        dlg.show();
    }

    private interface TextInputAction {
        void onSubmit(String text);
    }

    private static void showTextInputDialog(final Context ctx, String header, String hint, String initial, String positiveText, final TextInputAction action) {
        final Dialog dlg = new Dialog(ctx);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Typeface tf = Res.sans(ctx);
        float dp = ctx.getResources().getDisplayMetrics().density;
        int textPrimary = Res.getColor(ctx, "TxtcolorHome", Res.isNightMode() ? Color.WHITE : Color.BLACK);
        int textSecondary = Res.getColor(ctx, "TxtcolorHome2", Res.isNightMode() ? 0xFFD1D0D0 : 0xFF636363);
        int inputBg = Res.getColor(ctx, "BgAct", Res.isNightMode() ? 0xFF3C3C3C : 0xFFF2F2F2);
        int btnOkBg = Res.getColor(ctx, "BtnPopLogin", 0xFF0277BD);
        int btnCancelBg = Res.getColor(ctx, "BtnPopLoginNo", 0xFFEF574B);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int radiusId = Res.drawable(ctx, "radius");
        if (radiusId != 0) box.setBackgroundResource(radiusId);
        else box.setBackgroundColor(Res.isNightMode() ? 0xFF181818 : Color.WHITE);
        box.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), (int) (14 * dp));

        TextView title = new TextView(ctx);
        title.setText(header);
        title.setTypeface(tf, Typeface.BOLD);
        title.setTextSize(15f);
        title.setTextColor(textPrimary);
        title.setGravity(Gravity.RIGHT);
        box.addView(title);

        final EditText input = new EditText(ctx);
        input.setHint(hint);
        input.setText(initial == null ? "" : initial);
        if (initial != null) input.setSelection(initial.length());
        input.setSingleLine(true);
        input.setTypeface(tf);
        input.setTextSize(13f);
        input.setTextColor(textPrimary);
        input.setHintTextColor(textSecondary);
        input.setBackgroundColor(inputBg);
        input.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        input.setPadding((int) (10 * dp), (int) (8 * dp), (int) (10 * dp), (int) (8 * dp));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * dp));
        ilp.topMargin = (int) (10 * dp);
        ilp.bottomMargin = (int) (14 * dp);
        input.setLayoutParams(ilp);
        box.addView(input);

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText("انصراف");
        cancelBtn.setTypeface(tf, Typeface.BOLD);
        cancelBtn.setTextSize(13f);
        cancelBtn.setTextColor(Color.WHITE);
        cancelBtn.setBackgroundColor(btnCancelBg);
        cancelBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, (int) (40 * dp), 1f);
        clp.rightMargin = (int) (6 * dp);
        cancelBtn.setLayoutParams(clp);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { dlg.dismiss(); }
        });
        btns.addView(cancelBtn);

        TextView okBtn = new TextView(ctx);
        okBtn.setText(positiveText);
        okBtn.setTypeface(tf, Typeface.BOLD);
        okBtn.setTextSize(13f);
        okBtn.setTextColor(Color.WHITE);
        okBtn.setBackgroundColor(btnOkBg);
        okBtn.setGravity(Gravity.CENTER);
        okBtn.setLayoutParams(new LinearLayout.LayoutParams(0, (int) (40 * dp), 1.3f));
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String txt = input.getText() == null ? "" : input.getText().toString().trim();
                if (txt.isEmpty()) {
                    Toast.makeText(ctx, "نام پوشه نمی‌تواند خالی باشد", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                } catch (Throwable ignored) {}
                dlg.dismiss();
                if (action != null) action.onSubmit(txt);
            }
        });
        btns.addView(okBtn);

        box.addView(btns);
        dlg.setContentView(box);
        configureDialogWindow(dlg, ctx);
        dlg.show();
    }

    private static void configureDialogWindow(Dialog dlg, Context ctx) {
        Window win = dlg.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            win.setLayout((int) (dm.widthPixels * 0.90f), ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private static void setViewBgColor(View root, Context ctx, String idName, int color) {
        View v = root.findViewById(Res.id(ctx, idName));
        if (v != null) v.setBackgroundColor(color);
    }

    private static void setVis(View root, Context ctx, String idName, int vis) {
        View v = root.findViewById(Res.id(ctx, idName));
        if (v != null) v.setVisibility(vis);
    }
}
