package com.delfan.madp.favs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import com.delfan.madp.Other.g;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StreamGrabber {
    private static final String TAG = "StreamGrabber";
    private static final String ADM_PACKAGE = "com.dv.adm";
    private static final int MAX_REDIRECTS = 10;
    private static final int TIMEOUT_MS = 7000;

    private static final Object LOCK = new Object();
    private static final WeakHashMap<Activity, PlayerSession> SESSIONS = new WeakHashMap<Activity, PlayerSession>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService RESOLVER_EXEC = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "StreamGrabberResolver");
            t.setDaemon(true);
            return t;
        }
    });
    private static final AtomicBoolean ADM_RESOLVING = new AtomicBoolean(false);

    private static final String[] IGNORED_ASSET_EXTS = {
            ".srt", ".vtt", ".ass", ".ssa", ".sub", ".idx", ".smi", ".ttml", ".dfxp",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico",
            ".ts", ".m4s", ".aac", ".cmfv", ".cmfa"
    };

    private static final String[] MEDIA_EXTS = {
            ".mkv", ".mp4", ".webm", ".avi", ".mov", ".flv", ".m4v", ".m3u8", ".mpd", ".mp3", ".m4a"
    };

    private static final class PlayerSession {
        final WeakReference<Activity> actRef;
        String rawUrl = "";
        String subTitleUrl = "";
        String movieTitle = "";
        String capturedUrl = "";
        boolean captured = false;
        boolean resolvingFallback = false;
        WeakReference<ImageView> btnRef;
        WeakReference<Dialog> dialogRef;
        WeakReference<TextView> statusViewRef;
        WeakReference<TextView> urlViewRef;

        PlayerSession(Activity act) {
            this.actRef = new WeakReference<Activity>(act);
        }
    }

    private StreamGrabber() {}

    private static PlayerSession getOrCreateSession(Activity act) {
        synchronized (LOCK) {
            PlayerSession s = SESSIONS.get(act);
            if (s == null) {
                s = new PlayerSession(act);
                SESSIONS.put(act, s);
            }
            return s;
        }
    }

    public static ExoPlayer.Builder configureBuilder(Activity act, ExoPlayer.Builder builder) {
        if (act == null || builder == null) return builder;
        getOrCreateSession(act);
        final WeakReference<Activity> actRef = new WeakReference<Activity>(act);

        TransferListener listener = new TransferListener() {
            @Override
            public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
                // Pre-redirect DataSpec only; wait for onTransferStart where DefaultHttpDataSource exposes final redirected URI.
            }

            @Override
            public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
                Uri uri = null;
                if (source != null) {
                    try {
                        uri = source.getUri();
                    } catch (Throwable ignored) {}
                }
                if (uri == null && dataSpec != null) {
                    uri = dataSpec.uri;
                }
                Activity a = actRef.get();
                if (a != null && uri != null) {
                    onStreamTransferUri(a, uri.toString());
                }
            }

            @Override
            public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {}

            @Override
            public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {}
        };

        try {
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true);
            DefaultDataSource.Factory dsFactory = new DefaultDataSource.Factory(act, httpFactory)
                    .setTransferListener(listener);
            builder.setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to install TransferListener MediaSourceFactory", t);
        }
        return builder;
    }

    public static void bindPlayerUi(final Activity act) {
        if (act == null) return;
        final ImageView btn = findDownloadButton(act);
        if (btn == null) return;

        PlayerSession session = getOrCreateSession(act);
        synchronized (LOCK) {
            session.btnRef = new WeakReference<ImageView>(btn);
        }

        try {
            int focusColor = Res.getColor(act, "BgDrMenu", 0xFF1E1E1E);
            g.q(act, btn, focusColor);
        } catch (Throwable ignored) {}

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDownloadButtonClicked(act);
            }
        });

        updateButtonUi(act, session);
    }

    public static void onStartPlayer(Activity act, String url, String subTitle) {
        if (act == null) return;
        bindPlayerUi(act);
        PlayerSession s = getOrCreateSession(act);
        String title = "";
        try {
            Intent intent = act.getIntent();
            if (intent != null && intent.getExtras() != null) {
                String t = intent.getExtras().getString("title");
                if (t != null) title = t.trim();
            }
        } catch (Throwable ignored) {}

        synchronized (LOCK) {
            s.rawUrl = url == null ? "" : url.trim();
            s.subTitleUrl = subTitle == null ? "" : subTitle.trim();
            s.movieTitle = title;
            s.capturedUrl = "";
            s.captured = false;
            s.resolvingFallback = false;
        }
        Log.d(TAG, "onStartPlayer rawUrl=" + s.rawUrl);
        updateButtonUi(act, s);
    }

    public static void onPlaybackStateChanged(Activity act, int playbackState) {
        if (act == null || playbackState != 3) return; // Player.STATE_READY == 3
        PlayerSession s;
        boolean needFallback;
        synchronized (LOCK) {
            s = SESSIONS.get(act);
            if (s == null) return;
            needFallback = !s.captured && !TextUtils.isEmpty(s.rawUrl);
        }
        if (needFallback) {
            triggerFallbackResolve(act, s);
        }
    }

    public static void onDestroyPlayer(Activity act) {
        if (act == null) return;
        PlayerSession s;
        synchronized (LOCK) {
            s = SESSIONS.remove(act);
        }
        if (s != null && s.dialogRef != null) {
            Dialog d = s.dialogRef.get();
            if (d != null && d.isShowing()) {
                try {
                    d.dismiss();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void onStreamTransferUri(final Activity act, final String uriStr) {
        if (act == null || TextUtils.isEmpty(uriStr) || isActivityDead(act)) return;
        String lower = uriStr.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return;
        }

        final PlayerSession s = getOrCreateSession(act);
        boolean shouldTriggerFallback = false;
        boolean changed = false;

        synchronized (LOCK) {
            if (isIgnoredAsset(s, uriStr)) {
                return;
            }
            if (uriStr.equals(s.rawUrl) && !hasDirectMediaExtension(uriStr)) {
                // Saw rawUrl in TransferListener; verify whether HTTP redirects before declaring final.
                shouldTriggerFallback = !s.captured && !s.resolvingFallback;
            } else {
                if (!s.captured || !uriStr.equals(s.capturedUrl)) {
                    s.capturedUrl = uriStr;
                    s.captured = true;
                    changed = true;
                }
            }
        }

        if (changed) {
            Log.d(TAG, "Captured direct stream URL: " + uriStr);
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    if (!isActivityDead(act)) {
                        updateButtonUi(act, s);
                        updateOpenDialogIfVisible(act, s);
                    }
                }
            });
        } else if (shouldTriggerFallback) {
            triggerFallbackResolve(act, s);
        }
    }

    private static void triggerFallbackResolve(final Activity act, final PlayerSession s) {
        final String raw;
        synchronized (LOCK) {
            if (s.captured || s.resolvingFallback || TextUtils.isEmpty(s.rawUrl)) {
                return;
            }
            s.resolvingFallback = true;
            raw = s.rawUrl;
        }

        RESOLVER_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                String resolved = resolveFinalStreamUrl(raw);
                boolean updated = false;
                synchronized (LOCK) {
                    s.resolvingFallback = false;
                    if (!s.captured && !TextUtils.isEmpty(resolved) && !isIgnoredAsset(s, resolved)) {
                        s.capturedUrl = resolved;
                        s.captured = true;
                        updated = true;
                    }
                }
                if (updated) {
                    Log.d(TAG, "Fallback resolved stream URL: " + resolved);
                    MAIN_HANDLER.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isActivityDead(act)) {
                                updateButtonUi(act, s);
                                updateOpenDialogIfVisible(act, s);
                            }
                        }
                    });
                }
            }
        });
    }

    private static ImageView findDownloadButton(Activity act) {
        int id = Res.id(act, "btn_download_stream");
        if (id == 0) return null;
        View v = act.findViewById(id);
        if (v instanceof ImageView) return (ImageView) v;
        int pvId = Res.id(act, "player_view");
        if (pvId != 0) {
            View pv = act.findViewById(pvId);
            if (pv != null) {
                View inner = pv.findViewById(id);
                if (inner instanceof ImageView) return (ImageView) inner;
            }
        }
        return null;
    }

    private static void updateButtonUi(Activity act, PlayerSession s) {
        if (act == null || s == null || isActivityDead(act)) return;
        ImageView btn = s.btnRef != null ? s.btnRef.get() : findDownloadButton(act);
        if (btn == null) return;

        boolean ready;
        synchronized (LOCK) {
            ready = s.captured && !TextUtils.isEmpty(s.capturedUrl);
        }

        int drwId = Res.drawable(act, ready ? "ic_player_download_filled" : "ic_player_download_outline");
        if (drwId != 0) {
            btn.setImageResource(drwId);
        }
        btn.setContentDescription(ready ? "لینک مستقیم استریم (آماده)" : "دانلود یا کپی لینک مستقیم");
    }

    private static void updateOpenDialogIfVisible(Activity act, PlayerSession s) {
        if (s == null || s.dialogRef == null) return;
        Dialog d = s.dialogRef.get();
        if (d == null || !d.isShowing()) return;
        TextView tvStatus = s.statusViewRef != null ? s.statusViewRef.get() : null;
        TextView tvUrl = s.urlViewRef != null ? s.urlViewRef.get() : null;
        applyDialogUrlState(act, s, tvStatus, tvUrl);
    }

    private static void applyDialogUrlState(Context ctx, PlayerSession s, TextView tvStatus, TextView tvUrl) {
        String url;
        boolean ready;
        synchronized (LOCK) {
            ready = s.captured && !TextUtils.isEmpty(s.capturedUrl);
            url = ready ? s.capturedUrl : s.rawUrl;
        }
        if (tvStatus != null) {
            if (ready) {
                tvStatus.setText("لینک نهایی آماده است");
                tvStatus.setTextColor(Res.isNightMode() ? 0xFF66BB6A : 0xFF2E7D32);
            } else {
                tvStatus.setText("در حال دریافت لینک نهایی...");
                tvStatus.setTextColor(Res.getColor(ctx, "BtnIncLogin", 0xFF0277BD));
            }
        }
        if (tvUrl != null) {
            tvUrl.setText(TextUtils.isEmpty(url) ? "لینک در دسترس نیست" : url);
        }
    }

    private static String activeSessionUrl(PlayerSession s) {
        synchronized (LOCK) {
            if (s.captured && !TextUtils.isEmpty(s.capturedUrl)) {
                return s.capturedUrl;
            }
            return s.rawUrl;
        }
    }

    public static void onDownloadButtonClicked(final Activity act) {
        if (act == null || isActivityDead(act)) return;
        final PlayerSession s = getOrCreateSession(act);

        boolean needResolve;
        String title;
        synchronized (LOCK) {
            needResolve = !s.captured && !TextUtils.isEmpty(s.rawUrl);
            title = s.movieTitle;
        }
        if (needResolve) {
            triggerFallbackResolve(act, s);
        }

        int layoutId = Res.layout(act, "dialog_player_download");
        if (layoutId == 0) {
            String url = activeSessionUrl(s);
            if (!TextUtils.isEmpty(url)) {
                copyToClipboard(act, url);
            }
            return;
        }

        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutId);
        dialog.setCancelable(true);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            int maxW = (int) (420f * dm.density);
            int width = Math.min((int) (dm.widthPixels * 0.90f), maxW);
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        TextView btnClose = (TextView) dialog.findViewById(Res.id(act, "BtnClose_StreamDlg"));
        TextView txtTitle = (TextView) dialog.findViewById(Res.id(act, "TxtMovieTitle_StreamDlg"));
        TextView txtStatus = (TextView) dialog.findViewById(Res.id(act, "TxtStatus_StreamDlg"));
        TextView txtUrl = (TextView) dialog.findViewById(Res.id(act, "TxtUrl_StreamDlg"));
        View btnOpen = dialog.findViewById(Res.id(act, "BtnOpenUrl_StreamDlg"));
        View btnCopy = dialog.findViewById(Res.id(act, "BtnCopyUrl_StreamDlg"));
        View btnAdm = dialog.findViewById(Res.id(act, "BtnAdmUrl_StreamDlg"));

        if (txtTitle != null) {
            if (!TextUtils.isEmpty(title)) {
                txtTitle.setText(title);
                txtTitle.setVisibility(View.VISIBLE);
            } else {
                txtTitle.setVisibility(View.GONE);
            }
        }

        synchronized (LOCK) {
            s.dialogRef = new WeakReference<Dialog>(dialog);
            s.statusViewRef = new WeakReference<TextView>(txtStatus);
            s.urlViewRef = new WeakReference<TextView>(txtUrl);
        }
        applyDialogUrlState(act, s, txtStatus, txtUrl);

        if (btnClose != null) {
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        }

        if (btnOpen != null) {
            btnOpen.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String url = activeSessionUrl(s);
                    if (TextUtils.isEmpty(url)) {
                        Toast.makeText(act, "لینکی یافت نشد", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    openUrlInExternalApp(act, url);
                }
            });
        }

        if (btnCopy != null) {
            btnCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String url = activeSessionUrl(s);
                    if (TextUtils.isEmpty(url)) {
                        Toast.makeText(act, "لینکی یافت نشد", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    copyToClipboard(act, url);
                    dialog.dismiss();
                }
            });
        }

        if (btnAdm != null) {
            btnAdm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String url = activeSessionUrl(s);
                    if (TextUtils.isEmpty(url)) {
                        Toast.makeText(act, "لینکی یافت نشد", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    if (!s.captured) {
                        openAdmFromMenu(act, url);
                    } else {
                        launchAdmWithFallback(act, url);
                    }
                }
            });
        }

        dialog.show();
    }

    public static void openAdmFromMenu(final Context ctx, final Object rawUrlObj) {
        final String rawUrl = rawUrlObj == null ? "" : String.valueOf(rawUrlObj).trim();
        if (ctx == null || TextUtils.isEmpty(rawUrl)) return;
        if (!ADM_RESOLVING.compareAndSet(false, true)) {
            return;
        }

        RESOLVER_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                final String finalUrl;
                try {
                    finalUrl = resolveFinalStreamUrl(rawUrl);
                } finally {
                    ADM_RESOLVING.set(false);
                }
                Log.d(TAG, "ADM menu resolved finalUrl=" + finalUrl + " from rawUrl=" + rawUrl);
                MAIN_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        launchAdmWithFallback(ctx, finalUrl);
                    }
                });
            }
        });
    }

    private static void launchAdmWithFallback(Context ctx, String url) {
        if (ctx == null || TextUtils.isEmpty(url)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setPackage(ADM_PACKAGE);
            ctx.startActivity(intent);
            Log.d(TAG, "Launched ADM package=" + ADM_PACKAGE + " uri=" + url);
        } catch (Throwable t) {
            Log.d(TAG, "ADM unavailable (" + t.getClass().getSimpleName() + "), showing install prompt");
            showAdmNotInstalledDialog(ctx);
        }
    }

    private static void showAdmNotInstalledDialog(final Context ctx) {
        if (ctx instanceof Activity && isActivityDead((Activity) ctx)) {
            return;
        }
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
            builder.setMessage("برای دانلود با دانلودر ADM باید این نرم افزار را نصب کنید");
            builder.setPositiveButton("نصب", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    try {
                        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ADM_PACKAGE));
                        market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(market);
                    } catch (Throwable e1) {
                        try {
                            Intent web = new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=" + ADM_PACKAGE)
                            );
                            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(web);
                        } catch (Throwable e2) {
                            Toast.makeText(ctx, "فروشگاه برنامه در دسترس نیست", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });
            builder.setNegativeButton("لغو", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            });
            builder.show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "برای دانلود با دانلودر ADM باید این نرم افزار را نصب کنید", Toast.LENGTH_LONG).show();
        }
    }

    private static void openUrlInExternalApp(Context ctx, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(ctx, "برنامه‌ای برای باز کردن این لینک یافت نشد", Toast.LENGTH_SHORT).show();
        }
    }

    private static void copyToClipboard(Context ctx, String url) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Direct Stream Link", url));
                Toast.makeText(ctx, "لینک مستقیم در کلیپ‌بورد کپی شد", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(ctx, "خطا در کپی کردن لینک", Toast.LENGTH_SHORT).show();
        }
    }

    public static String resolveFinalStreamUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) return rawUrl;
        String current = rawUrl.trim();
        String lowerInit = current.toLowerCase(Locale.US);
        if (!lowerInit.startsWith("http://") && !lowerInit.startsWith("https://")) {
            return current;
        }

        String ua = null;
        try {
            ua = System.getProperty("http.agent");
        } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(ua)) {
            ua = "Mymoviebox";
        }

        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = null;
            try {
                URL urlObj = new URL(current);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", ua);
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Range", "bytes=0-0");
                conn.setRequestProperty("Connection", "close");

                int code = conn.getResponseCode();
                if (code == 300 || code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = conn.getHeaderField("Location");
                    if (TextUtils.isEmpty(loc)) {
                        break;
                    }
                    loc = loc.trim().replace(" ", "%20");
                    URL nextUrl = new URL(urlObj, loc);
                    String nextStr = nextUrl.toExternalForm();
                    String nextLower = nextStr.toLowerCase(Locale.US);
                    if (!nextLower.startsWith("http://") && !nextLower.startsWith("https://")) {
                        break;
                    }
                    current = nextStr;
                    continue;
                }
                break;
            } catch (Throwable t) {
                Log.d(TAG, "Redirect resolve stopped at hop " + hop + ": " + t.getMessage());
                break;
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {}
                }
            }
        }
        return current;
    }

    private static boolean isIgnoredAsset(PlayerSession s, String url) {
        if (TextUtils.isEmpty(url)) return true;
        if (s != null && !TextUtils.isEmpty(s.subTitleUrl) && url.equalsIgnoreCase(s.subTitleUrl)) {
            return true;
        }
        String path = stripQueryAndFragment(url).toLowerCase(Locale.US);
        if (s != null && !TextUtils.isEmpty(s.subTitleUrl)) {
            String subPath = stripQueryAndFragment(s.subTitleUrl).toLowerCase(Locale.US);
            if (!subPath.isEmpty() && path.equals(subPath)) {
                return true;
            }
        }
        for (String ext : IGNORED_ASSET_EXTS) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDirectMediaExtension(String url) {
        String path = stripQueryAndFragment(url).toLowerCase(Locale.US);
        for (String ext : MEDIA_EXTS) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String stripQueryAndFragment(String url) {
        int end = url.length();
        int q = url.indexOf('?');
        if (q >= 0 && q < end) end = q;
        int h = url.indexOf('#');
        if (h >= 0 && h < end) end = h;
        return url.substring(0, end);
    }

    private static boolean isActivityDead(Activity act) {
        if (act == null || act.isFinishing()) return true;
        if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) return true;
        return false;
    }
}
