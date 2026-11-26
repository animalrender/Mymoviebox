package com.delfan.madp.favs;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StreamGrabber {
    private static final String TAG = "StreamGrabber";
    private static final String ADM_PACKAGE = "com.dv.adm";
    private static final String PREFS_NAME = "offline_stream_grabber";
    private static final String KEY_GRABBER_ENABLED = "grabber_enabled";
    private static final int MAX_REDIRECTS = 10;
    private static final int TIMEOUT_MS = 7000;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_ITEMS_PER_SESSION = 64;

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
    private static volatile Boolean cachedGrabberEnabled = null;

    private static final Pattern PAT_HLS_RES = Pattern.compile("RESOLUTION=(\\d+)x(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_HLS_BW = Pattern.compile("BANDWIDTH=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_HLS_TYPE = Pattern.compile("TYPE=([^,\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_HLS_NAME = Pattern.compile("NAME=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_HLS_LANG = Pattern.compile("LANGUAGE=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_HLS_URI = Pattern.compile("URI=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_DASH_BASEURL = Pattern.compile("<BaseURL[^>]*>([^<]+)</BaseURL>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_QUALITY_HINT = Pattern.compile("(?:^|[^0-9])(2160|1440|1080|720|480|360|240)p?(?:[^0-9]|$)", Pattern.CASE_INSENSITIVE);

    private static final String[] IMAGE_EXTS = {
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".ico"
    };
    private static final String[] HARD_IGNORED_SEGMENT_EXTS = {
            ".m4s", ".cmfv", ".cmfa"
    };
    private static final String[] SUBTITLE_EXTS = {
            ".srt", ".vtt", ".ass", ".ssa", ".sub", ".idx", ".smi", ".ttml", ".dfxp"
    };
    private static final String[] AUDIO_EXTS = {
            ".m4a", ".mp3", ".ogg", ".opus", ".flac", ".wav", ".aac"
    };
    private static final String[] VIDEO_EXTS = {
            ".mkv", ".mp4", ".webm", ".avi", ".mov", ".flv", ".m4v", ".ts"
    };

    public static final class GrabbedItem {
        final String url;
        String originUrl = "";
        String category = "video"; // video, hls, dash, audio, subtitle, other
        String formatLabel = "STREAM";
        String qualityMeta = "";
        String displayName = "";
        long sizeBytes = -1L;
        boolean redirected = false;
        boolean redirectChecked = false;
        final long order;

        GrabbedItem(String url, long order) {
            this.url = url;
            this.order = order;
        }
    }

    private static final class PlayerSession {
        final WeakReference<Activity> actRef;
        String rawUrl = "";
        String subTitleUrl = "";
        String movieTitle = "";
        final LinkedHashMap<String, GrabbedItem> items = new LinkedHashMap<String, GrabbedItem>();
        long nextOrder = 1L;
        boolean hasPlaylist = false;
        boolean resolvingFallback = false;
        String selectedUrl = "";
        String activeFilter = "all"; // all, video, stream, extra
        WeakReference<ImageView> btnDownloadRef;
        WeakReference<ImageView> btnToggleRef;
        WeakReference<Dialog> dialogRef;
        WeakReference<DialogUi> dialogUiRef;

        PlayerSession(Activity act) {
            this.actRef = new WeakReference<Activity>(act);
        }
    }

    private static final class DialogUi {
        TextView btnToggle;
        TextView txtStatus;
        View rowFilters;
        TextView tabAll;
        TextView tabVideo;
        TextView tabStream;
        TextView tabExtra;
        View scrollLinks;
        LinearLayout listContainer;
        TextView txtEmpty;
        TextView txtUrl;
        int dialogWidthPx;
    }

    private StreamGrabber() {}

    public static boolean isGrabberEnabled(Context ctx) {
        Boolean cached = cachedGrabberEnabled;
        if (cached != null) return cached.booleanValue();
        boolean val = true;
        if (ctx != null) {
            try {
                SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                val = sp.getBoolean(KEY_GRABBER_ENABLED, true);
            } catch (Throwable ignored) {}
        }
        cachedGrabberEnabled = Boolean.valueOf(val);
        return val;
    }

    public static boolean toggleGrabberEnabled(final Activity act) {
        boolean next = !isGrabberEnabled(act);
        cachedGrabberEnabled = Boolean.valueOf(next);
        if (act != null) {
            try {
                SharedPreferences sp = act.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KEY_GRABBER_ENABLED, next).apply();
            } catch (Throwable ignored) {}
            Toast.makeText(act, next ? "گربر لینک فعال شد" : "گربر لینک غیرفعال شد", Toast.LENGTH_SHORT).show();
        }
        Log.d(TAG, "Grabber toggled: enabled=" + next);
        if (act != null && !isActivityDead(act)) {
            final PlayerSession s = getOrCreateSession(act);
            if (next) {
                triggerManualActiveScanIfNeeded(act, s);
            }
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    if (!isActivityDead(act)) {
                        updateButtonsUi(act, s);
                        updateOpenDialogIfVisible(act, s);
                    }
                }
            });
        }
        return next;
    }

    private static void triggerManualActiveScanIfNeeded(final Activity act, final PlayerSession s) {
        final String raw;
        final String sub;
        boolean needScan;
        synchronized (LOCK) {
            raw = s.rawUrl;
            sub = s.subTitleUrl;
            needScan = !hasPrimaryVideoOrStream(s) && !TextUtils.isEmpty(raw);
        }
        if (!needScan) return;
        triggerFallbackResolve(act, s, raw);
        if (!TextUtils.isEmpty(sub) && isHttpUrl(sub)) {
            RESOLVER_EXEC.execute(new Runnable() {
                @Override
                public void run() {
                    if (!isGrabberEnabled(act)) return;
                    ResolveResult subRes = resolveFinalStreamWithMeta(sub);
                    if (!isGrabberEnabled(act) || TextUtils.isEmpty(subRes.finalUrl)) return;
                    onNetworkResourceCaptured(act, sub, subRes.finalUrl, subRes.contentType, subRes.sizeBytes, subRes.dispName, null, true);
                }
            });
        }
    }

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

        try {
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true);
            final DefaultDataSource.Factory baseFactory = new DefaultDataSource.Factory(act, httpFactory);
            DataSource.Factory grabbingFactory = new DataSource.Factory() {
                @Override
                public DataSource createDataSource() {
                    return new GrabbingDataSource(actRef, baseFactory.createDataSource());
                }
            };
            builder.setMediaSourceFactory(new DefaultMediaSourceFactory(grabbingFactory));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to install GrabbingDataSourceFactory", t);
        }
        return builder;
    }

    private static final class GrabbingDataSource implements DataSource {
        private final WeakReference<Activity> actRef;
        private final DataSource delegate;
        private ByteArrayOutputStream manifestBuf;
        private String manifestBaseUrl = "";
        private String manifestCategory = "";
        private boolean manifestParsed = false;

        GrabbingDataSource(WeakReference<Activity> actRef, DataSource delegate) {
            this.actRef = actRef;
            this.delegate = delegate;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            if (delegate != null && transferListener != null) {
                delegate.addTransferListener(transferListener);
            }
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            manifestBuf = null;
            manifestBaseUrl = "";
            manifestCategory = "";
            manifestParsed = false;

            String reqUrl = (dataSpec != null && dataSpec.uri != null) ? dataSpec.uri.toString() : "";
            Activity act = actRef.get();
            boolean enabled = isGrabberEnabled(act);

            long length;
            try {
                length = delegate.open(dataSpec);
            } catch (IOException ioe) {
                if (enabled && act != null && !isActivityDead(act) && isHttpUrl(reqUrl)) {
                    try {
                        Uri errUri = delegate.getUri();
                        String finalErrUrl = errUri != null ? errUri.toString() : "";
                        if (!TextUtils.isEmpty(finalErrUrl) && !finalErrUrl.equals(reqUrl)) {
                            onNetworkResourceCaptured(act, reqUrl, finalErrUrl, null, -1L, null, null, true);
                        } else {
                            PlayerSession s = getOrCreateSession(act);
                            triggerFallbackResolve(act, s, reqUrl);
                        }
                    } catch (Throwable ignored) {}
                }
                throw ioe;
            }

            if (enabled && act != null && !isActivityDead(act)) {
                try {
                    Uri u = delegate.getUri();
                    String finalUrl = u != null ? u.toString() : reqUrl;
                    if (isHttpUrl(finalUrl)) {
                        Map<String, List<String>> hdrs = delegate.getResponseHeaders();
                        String contentType = findHeader(hdrs, "Content-Type");
                        long totalSize = parseTotalByteSize(hdrs, length);
                        String dispName = parseContentDispositionFilename(findHeader(hdrs, "Content-Disposition"));

                        onNetworkResourceCaptured(act, reqUrl, finalUrl, contentType, totalSize, dispName, null, true);

                        String lowerPath = stripQueryAndFragment(finalUrl).toLowerCase(Locale.US);
                        String lowerCt = contentType == null ? "" : contentType.toLowerCase(Locale.US);
                        if (lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".m3u")
                                || lowerCt.contains("mpegurl")) {
                            manifestBuf = new ByteArrayOutputStream();
                            manifestBaseUrl = finalUrl;
                            manifestCategory = "hls";
                        } else if (lowerPath.endsWith(".mpd") || lowerCt.contains("dash+xml")) {
                            manifestBuf = new ByteArrayOutputStream();
                            manifestBaseUrl = finalUrl;
                            manifestCategory = "dash";
                        }
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "Non-fatal grab open error: " + t.getMessage());
                }
            }
            return length;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (manifestBuf != null && !manifestParsed) {
                try {
                    if (n > 0 && manifestBuf.size() < MAX_MANIFEST_BYTES) {
                        int allow = Math.min(n, MAX_MANIFEST_BYTES - manifestBuf.size());
                        manifestBuf.write(buffer, offset, allow);
                    } else if (n == -1) {
                        finishManifestParse();
                    }
                } catch (Throwable ignored) {}
            }
            return n;
        }

        @Override
        public Uri getUri() {
            return delegate.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return delegate.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            try {
                if (manifestBuf != null && !manifestParsed) {
                    finishManifestParse();
                }
            } catch (Throwable ignored) {}
            delegate.close();
        }

        private void finishManifestParse() {
            manifestParsed = true;
            Activity act = actRef.get();
            if (act == null || isActivityDead(act) || !isGrabberEnabled(act) || manifestBuf == null) {
                manifestBuf = null;
                return;
            }
            byte[] data = manifestBuf.toByteArray();
            manifestBuf = null;
            if (data.length == 0) return;
            String text = new String(data, StandardCharsets.UTF_8);
            if ("hls".equals(manifestCategory)) {
                parseAndAddHlsManifest(act, manifestBaseUrl, text);
            } else if ("dash".equals(manifestCategory)) {
                parseAndAddDashManifest(act, manifestBaseUrl, text);
            }
        }
    }

    public static void bindPlayerUi(final Activity act) {
        if (act == null) return;
        final ImageView btnDownload = findPlayerViewById(act, "btn_download_stream");
        final ImageView btnToggle = findPlayerViewById(act, "btn_grabber_toggle");
        if (btnDownload == null && btnToggle == null) return;

        PlayerSession session = getOrCreateSession(act);
        synchronized (LOCK) {
            if (btnDownload != null) session.btnDownloadRef = new WeakReference<ImageView>(btnDownload);
            if (btnToggle != null) session.btnToggleRef = new WeakReference<ImageView>(btnToggle);
        }

        int focusColor = Res.getColor(act, "BgDrMenu", 0xFF1E1E1E);
        if (btnDownload != null) {
            try {
                g.q(act, btnDownload, focusColor);
            } catch (Throwable ignored) {}
            btnDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onDownloadButtonClicked(act);
                }
            });
        }
        if (btnToggle != null) {
            try {
                g.q(act, btnToggle, focusColor);
            } catch (Throwable ignored) {}
            btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleGrabberEnabled(act);
                }
            });
        }

        updateButtonsUi(act, session);
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
            s.items.clear();
            s.nextOrder = 1L;
            s.hasPlaylist = false;
            s.resolvingFallback = false;
            s.selectedUrl = "";
            s.activeFilter = "all";
        }
        Log.d(TAG, "onStartPlayer rawUrl=" + s.rawUrl + " grabberEnabled=" + isGrabberEnabled(act));
        updateButtonsUi(act, s);
    }

    public static void onPlaybackStateChanged(Activity act, int playbackState) {
        if (act == null || playbackState != 3) return; // Player.STATE_READY == 3
        if (!isGrabberEnabled(act)) return;
        PlayerSession s;
        boolean needFallback;
        String raw;
        synchronized (LOCK) {
            s = SESSIONS.get(act);
            if (s == null) return;
            raw = s.rawUrl;
            needFallback = !hasPrimaryVideoOrStream(s) && !TextUtils.isEmpty(raw);
        }
        if (needFallback) {
            triggerFallbackResolve(act, s, raw);
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

    private static void parseAndAddHlsManifest(Activity act, String baseUrl, String text) {
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(text)) return;
        String[] lines = text.split("\\r?\\n");
        String pendingQuality = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-MEDIA:")) {
                String uri = matchGroup1(PAT_HLS_URI, line);
                if (!TextUtils.isEmpty(uri)) {
                    String abs = resolveRelativeUrl(baseUrl, uri);
                    String type = matchGroup1(PAT_HLS_TYPE, line);
                    String name = matchGroup1(PAT_HLS_NAME, line);
                    String lang = matchGroup1(PAT_HLS_LANG, line);
                    String cat = "SUBTITLES".equalsIgnoreCase(type) ? "subtitle"
                            : ("AUDIO".equalsIgnoreCase(type) ? "audio" : "hls");
                    String meta = buildMeta(name, lang);
                    if (isHttpUrl(abs)) {
                        onNetworkResourceCaptured(act, abs, abs,
                                "subtitle".equals(cat) ? "text/vtt" : "application/vnd.apple.mpegurl",
                                -1L, null, TextUtils.isEmpty(meta) ? type : meta, true, cat);
                    }
                }
                pendingQuality = null;
                continue;
            }

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                String resH = null;
                Matcher mRes = PAT_HLS_RES.matcher(line);
                if (mRes.find()) {
                    resH = mRes.group(2) + "p";
                }
                String bwLabel = null;
                Matcher mBw = PAT_HLS_BW.matcher(line);
                if (mBw.find()) {
                    try {
                        long bps = Long.parseLong(mBw.group(1));
                        bwLabel = formatBitrate(bps);
                    } catch (Throwable ignored) {}
                }
                pendingQuality = buildMeta(resH, bwLabel);
                if (TextUtils.isEmpty(pendingQuality)) {
                    pendingQuality = "Stream";
                }
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }

            if (pendingQuality != null) {
                String abs = resolveRelativeUrl(baseUrl, line);
                if (isHttpUrl(abs)) {
                    onNetworkResourceCaptured(act, abs, abs, "application/vnd.apple.mpegurl",
                            -1L, null, pendingQuality, true, "hls");
                }
                pendingQuality = null;
            }
        }
    }

    private static void parseAndAddDashManifest(Activity act, String baseUrl, String text) {
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(text)) return;
        Matcher m = PAT_DASH_BASEURL.matcher(text);
        int count = 0;
        while (m.find() && count < 16) {
            String rel = m.group(1).trim();
            if (TextUtils.isEmpty(rel)) continue;
            String abs = resolveRelativeUrl(baseUrl, rel);
            if (isHttpUrl(abs) && !abs.equals(baseUrl)) {
                onNetworkResourceCaptured(act, abs, abs, null, -1L, null, "DASH Base", true, null);
                count++;
            }
        }
    }

    private static void onNetworkResourceCaptured(
            Activity act,
            String originUrl,
            String finalUrl,
            String contentType,
            long sizeBytes,
            String dispName,
            String qualityHint,
            boolean redirectChecked) {
        onNetworkResourceCaptured(act, originUrl, finalUrl, contentType, sizeBytes, dispName, qualityHint, redirectChecked, null);
    }

    private static void onNetworkResourceCaptured(
            final Activity act,
            String originUrl,
            String finalUrl,
            String contentType,
            long sizeBytes,
            String dispName,
            String qualityHint,
            boolean redirectChecked,
            String forcedCategory) {
        if (act == null || isActivityDead(act) || !isGrabberEnabled(act)) return;
        if (!isHttpUrl(finalUrl)) return;

        final PlayerSession s = getOrCreateSession(act);
        String cleanFinal = finalUrl.trim();
        String cleanOrigin = originUrl == null ? "" : originUrl.trim();
        boolean changed = false;

        synchronized (LOCK) {
            if (isAlwaysIgnoredResource(cleanFinal, contentType, s.hasPlaylist)) {
                return;
            }

            String category = !TextUtils.isEmpty(forcedCategory)
                    ? forcedCategory
                    : classifyCategory(s, cleanFinal, contentType, sizeBytes);
            if ("hls".equals(category) || "dash".equals(category)) {
                s.hasPlaylist = true;
            }

            String formatLabel = detectFormatLabel(cleanFinal, contentType, category);
            boolean redirected = !TextUtils.isEmpty(cleanOrigin) && !cleanOrigin.equals(cleanFinal);
            String pathQuality = extractQualityFromUrl(cleanFinal);
            String baseQuality = !TextUtils.isEmpty(qualityHint) ? qualityHint : pathQuality;
            if ("hls".equals(category) && TextUtils.isEmpty(baseQuality) && TextUtils.isEmpty(forcedCategory)) {
                baseQuality = "Master";
            }
            String fullMeta = buildMeta(baseQuality, redirected ? "ریدایرکت شده" : null);
            String displayName = !TextUtils.isEmpty(dispName) ? dispName : extractDisplayName(cleanFinal);

            GrabbedItem item = s.items.get(cleanFinal);
            if (item == null) {
                if (s.items.size() >= MAX_ITEMS_PER_SESSION) {
                    return;
                }
                item = new GrabbedItem(cleanFinal, s.nextOrder++);
                item.originUrl = cleanOrigin;
                item.category = category;
                item.formatLabel = formatLabel;
                item.qualityMeta = fullMeta;
                item.displayName = displayName;
                item.sizeBytes = sizeBytes;
                item.redirected = redirected;
                item.redirectChecked = redirectChecked;
                s.items.put(cleanFinal, item);
                changed = true;
                Log.d(TAG, "Grabbed [" + category + "/" + formatLabel + "] url=" + cleanFinal);
            } else {
                if (sizeBytes > 0 && item.sizeBytes <= 0) {
                    item.sizeBytes = sizeBytes;
                    changed = true;
                }
                if (!item.redirected && redirected) {
                    item.redirected = true;
                    item.originUrl = cleanOrigin;
                    changed = true;
                }
                if (TextUtils.isEmpty(item.qualityMeta) && !TextUtils.isEmpty(fullMeta)) {
                    item.qualityMeta = fullMeta;
                    changed = true;
                } else if (redirected && !item.qualityMeta.contains("ریدایرکت")) {
                    item.qualityMeta = buildMeta(item.qualityMeta, "ریدایرکت شده");
                    changed = true;
                }
                if ("other".equals(item.category) && !"other".equals(category)) {
                    item.category = category;
                    item.formatLabel = formatLabel;
                    changed = true;
                }
                if (!item.redirectChecked && redirectChecked) {
                    item.redirectChecked = true;
                    changed = true;
                }
            }

            if (TextUtils.isEmpty(s.selectedUrl) || (!isPrimaryCategory(getSelectedCategoryLocked(s)) && isPrimaryCategory(category))) {
                s.selectedUrl = cleanFinal;
            }
        }

        if (changed) {
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    if (!isActivityDead(act)) {
                        updateButtonsUi(act, s);
                        updateOpenDialogIfVisible(act, s);
                    }
                }
            });
        }
    }

    private static String getSelectedCategoryLocked(PlayerSession s) {
        if (TextUtils.isEmpty(s.selectedUrl)) return "";
        GrabbedItem it = s.items.get(s.selectedUrl);
        return it == null ? "" : it.category;
    }

    private static boolean hasPrimaryVideoOrStream(PlayerSession s) {
        for (GrabbedItem item : s.items.values()) {
            if (isPrimaryCategory(item.category)) return true;
        }
        return false;
    }

    private static boolean isPrimaryCategory(String cat) {
        return "video".equals(cat) || "hls".equals(cat) || "dash".equals(cat);
    }

    private static void triggerFallbackResolve(final Activity act, final PlayerSession s, final String rawUrl) {
        if (act == null || TextUtils.isEmpty(rawUrl) || !isGrabberEnabled(act)) return;
        synchronized (LOCK) {
            if (s.resolvingFallback) return;
            s.resolvingFallback = true;
        }

        RESOLVER_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!isGrabberEnabled(act)) return;
                    ResolveResult res = resolveFinalStreamWithMeta(rawUrl);
                    if (!isGrabberEnabled(act) || TextUtils.isEmpty(res.finalUrl)) return;
                    onNetworkResourceCaptured(act, rawUrl, res.finalUrl, res.contentType, res.sizeBytes, res.dispName, null, true);

                    String lowerPath = stripQueryAndFragment(res.finalUrl).toLowerCase(Locale.US);
                    String lowerCt = res.contentType == null ? "" : res.contentType.toLowerCase(Locale.US);
                    if (lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".m3u") || lowerCt.contains("mpegurl")) {
                        String body = fetchSmallText(res.finalUrl);
                        if (!TextUtils.isEmpty(body) && isGrabberEnabled(act)) {
                            parseAndAddHlsManifest(act, res.finalUrl, body);
                        }
                    } else if (lowerPath.endsWith(".mpd") || lowerCt.contains("dash+xml")) {
                        String body = fetchSmallText(res.finalUrl);
                        if (!TextUtils.isEmpty(body) && isGrabberEnabled(act)) {
                            parseAndAddDashManifest(act, res.finalUrl, body);
                        }
                    }
                } finally {
                    synchronized (LOCK) {
                        s.resolvingFallback = false;
                    }
                }
            }
        });
    }

    private static ImageView findPlayerViewById(Activity act, String idName) {
        int id = Res.id(act, idName);
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

    private static void updateButtonsUi(Activity act, PlayerSession s) {
        if (act == null || s == null || isActivityDead(act)) return;
        boolean enabled = isGrabberEnabled(act);

        ImageView btnToggle = s.btnToggleRef != null ? s.btnToggleRef.get() : findPlayerViewById(act, "btn_grabber_toggle");
        if (btnToggle != null) {
            int toggleDrw = Res.drawable(act, enabled ? "ic_grabber_on" : "ic_grabber_off");
            if (toggleDrw != 0) {
                btnToggle.setImageResource(toggleDrw);
            }
            btnToggle.setContentDescription(enabled ? "گربر لینک: روشن" : "گربر لینک: خاموش");
        }

        ImageView btnDownload = s.btnDownloadRef != null ? s.btnDownloadRef.get() : findPlayerViewById(act, "btn_download_stream");
        if (btnDownload == null) return;

        int count;
        boolean hasAny;
        synchronized (LOCK) {
            count = s.items.size();
            hasAny = count > 0;
        }

        int drwId = Res.drawable(act, hasAny ? "ic_player_download_filled" : "ic_player_download_outline");
        if (drwId != 0) {
            btnDownload.setImageResource(drwId);
        }
        btnDownload.setAlpha((enabled || hasAny) ? 1.0f : 0.65f);

        if (hasAny) {
            btnDownload.setContentDescription("لینک مستقیم استریم (آماده - " + count + " لینک)");
        } else if (!enabled) {
            btnDownload.setContentDescription("گربر لینک غیرفعال است");
        } else {
            btnDownload.setContentDescription("دانلود یا کپی لینک مستقیم");
        }
    }

    private static void updateOpenDialogIfVisible(Activity act, PlayerSession s) {
        if (s == null || s.dialogRef == null || s.dialogUiRef == null) return;
        Dialog d = s.dialogRef.get();
        DialogUi ui = s.dialogUiRef.get();
        if (d == null || !d.isShowing() || ui == null) return;
        renderDialogState(act, s, ui);
    }

    private static List<GrabbedItem> getSortedSnapshotLocked(PlayerSession s) {
        List<GrabbedItem> list = new ArrayList<GrabbedItem>(s.items.values());
        Collections.sort(list, new Comparator<GrabbedItem>() {
            @Override
            public int compare(GrabbedItem a, GrabbedItem b) {
                int pa = categoryRank(a.category);
                int pb = categoryRank(b.category);
                if (pa != pb) return pa - pb;
                return a.order < b.order ? -1 : (a.order > b.order ? 1 : 0);
            }
        });
        return list;
    }

    private static int categoryRank(String cat) {
        if ("video".equals(cat)) return 0;
        if ("hls".equals(cat)) return 1;
        if ("dash".equals(cat)) return 2;
        if ("audio".equals(cat)) return 3;
        if ("subtitle".equals(cat)) return 4;
        return 5;
    }

    private static boolean matchesFilter(GrabbedItem item, String filter) {
        if ("all".equals(filter)) return true;
        if ("video".equals(filter)) return "video".equals(item.category);
        if ("stream".equals(filter)) return "hls".equals(item.category) || "dash".equals(item.category);
        if ("extra".equals(filter)) {
            return "audio".equals(item.category) || "subtitle".equals(item.category) || "other".equals(item.category);
        }
        return true;
    }

    private static void renderDialogState(final Activity act, final PlayerSession s, final DialogUi ui) {
        boolean enabled = isGrabberEnabled(act);
        List<GrabbedItem> allItems;
        String activeFilter;
        String selectedUrl;
        String rawFallback;

        synchronized (LOCK) {
            allItems = getSortedSnapshotLocked(s);
            activeFilter = s.activeFilter;
            if (TextUtils.isEmpty(s.selectedUrl) && !allItems.isEmpty()) {
                s.selectedUrl = allItems.get(0).url;
            }
            selectedUrl = s.selectedUrl;
            rawFallback = s.rawUrl;
        }

        int totalCount = allItems.size();
        int videoCount = 0;
        int streamCount = 0;
        int extraCount = 0;
        List<GrabbedItem> filtered = new ArrayList<GrabbedItem>();
        for (int i = 0; i < totalCount; i++) {
            GrabbedItem it = allItems.get(i);
            if ("video".equals(it.category)) videoCount++;
            else if ("hls".equals(it.category) || "dash".equals(it.category)) streamCount++;
            else extraCount++;
            if (matchesFilter(it, activeFilter)) {
                filtered.add(it);
            }
        }
        if (filtered.isEmpty() && !"all".equals(activeFilter) && totalCount > 0) {
            synchronized (LOCK) {
                s.activeFilter = "all";
            }
            activeFilter = "all";
            filtered = allItems;
        }

        if (ui.btnToggle != null) {
            ui.btnToggle.setText(enabled ? "گربر: روشن" : "گربر: خاموش");
            int bgId = Res.drawable(act, enabled ? "stream_toggle_on_bg" : "stream_toggle_off_bg");
            if (bgId != 0) ui.btnToggle.setBackgroundResource(bgId);
            ui.btnToggle.setTextColor(enabled
                    ? (Res.isNightMode() ? 0xFF81C784 : 0xFF2E7D32)
                    : Res.getColor(act, "TxtcolorHome2", 0xFF757575));
        }

        if (ui.txtStatus != null) {
            if (!enabled && totalCount == 0) {
                ui.txtStatus.setText("گربر خاموش است");
                ui.txtStatus.setTextColor(Res.getColor(act, "TxtcolorHome2", 0xFF757575));
            } else if (totalCount > 0) {
                String statusTxt = totalCount == 1
                        ? "۱ لینک نهایی آماده است"
                        : totalCount + " لینک از شبکه دریافت شد";
                if (!enabled) {
                    statusTxt += " (متوقف)";
                }
                ui.txtStatus.setText(statusTxt);
                ui.txtStatus.setTextColor(Res.isNightMode() ? 0xFF66BB6A : 0xFF2E7D32);
            } else {
                ui.txtStatus.setText("در حال دریافت لینک‌های شبکه...");
                ui.txtStatus.setTextColor(Res.getColor(act, "BtnIncLogin", 0xFF0277BD));
            }
        }

        updateFilterChip(act, ui.tabAll, "همه (" + totalCount + ")", "all".equals(activeFilter), true);
        updateFilterChip(act, ui.tabVideo, "ویدیو (" + videoCount + ")", "video".equals(activeFilter), videoCount > 0);
        updateFilterChip(act, ui.tabStream, "HLS / DASH (" + streamCount + ")", "stream".equals(activeFilter), streamCount > 0);
        updateFilterChip(act, ui.tabExtra, "صدا / زیرنویس (" + extraCount + ")", "extra".equals(activeFilter), extraCount > 0);
        if (ui.rowFilters != null) {
            ui.rowFilters.setVisibility(totalCount > 0 ? View.VISIBLE : View.GONE);
        }

        if (ui.listContainer != null) {
            ui.listContainer.removeAllViews();
            int itemLayoutId = Res.layout(act, "item_grabbed_link");
            LayoutInflater inflater = LayoutInflater.from(act);
            if (itemLayoutId != 0) {
                int idCardRoot = Res.id(act, "CardRoot_GrabItem");
                int idSelected = Res.id(act, "TxtItemSelected_GrabItem");
                int idSize = Res.id(act, "TxtItemSize_GrabItem");
                int idQuality = Res.id(act, "TxtItemQuality_GrabItem");
                int idType = Res.id(act, "TxtItemType_GrabItem");
                int idName = Res.id(act, "TxtItemName_GrabItem");
                int idUrl = Res.id(act, "TxtItemUrl_GrabItem");
                int bgNormal = Res.drawable(act, "stream_link_item_bg");
                int bgSelected = Res.drawable(act, "stream_link_item_selected_bg");

                for (int i = 0; i < filtered.size(); i++) {
                    final GrabbedItem item = filtered.get(i);
                    View card = inflater.inflate(itemLayoutId, ui.listContainer, false);
                    boolean isSel = item.url.equals(selectedUrl);

                    View rootCard = idCardRoot != 0 ? card.findViewById(idCardRoot) : card;
                    if (rootCard != null) {
                        if (isSel && bgSelected != 0) rootCard.setBackgroundResource(bgSelected);
                        else if (!isSel && bgNormal != 0) rootCard.setBackgroundResource(bgNormal);
                    }

                    TextView tvSel = idSelected != 0 ? (TextView) card.findViewById(idSelected) : null;
                    if (tvSel != null) {
                        tvSel.setVisibility(isSel ? View.VISIBLE : View.GONE);
                    }

                    TextView tvSize = idSize != 0 ? (TextView) card.findViewById(idSize) : null;
                    if (tvSize != null) {
                        if (item.sizeBytes > 0) {
                            tvSize.setText(formatByteSize(item.sizeBytes));
                            tvSize.setVisibility(View.VISIBLE);
                        } else {
                            tvSize.setVisibility(View.GONE);
                        }
                    }

                    TextView tvQual = idQuality != 0 ? (TextView) card.findViewById(idQuality) : null;
                    if (tvQual != null) {
                        if (!TextUtils.isEmpty(item.qualityMeta)) {
                            tvQual.setText(item.qualityMeta);
                            tvQual.setVisibility(View.VISIBLE);
                        } else {
                            tvQual.setVisibility(View.GONE);
                        }
                    }

                    TextView tvType = idType != 0 ? (TextView) card.findViewById(idType) : null;
                    if (tvType != null) {
                        tvType.setText(formatCategoryBadge(item.category, item.formatLabel));
                    }

                    TextView tvName = idName != 0 ? (TextView) card.findViewById(idName) : null;
                    if (tvName != null) {
                        tvName.setText(item.displayName);
                    }

                    TextView tvItemUrl = idUrl != 0 ? (TextView) card.findViewById(idUrl) : null;
                    if (tvItemUrl != null) {
                        tvItemUrl.setText(item.url);
                    }

                    View clickTarget = rootCard != null ? rootCard : card;
                    clickTarget.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            synchronized (LOCK) {
                                s.selectedUrl = item.url;
                            }
                            renderDialogState(act, s, ui);
                        }
                    });
                    ui.listContainer.addView(card);
                }
            }
        }

        if (ui.scrollLinks != null && ui.listContainer != null) {
            if (filtered.isEmpty()) {
                ui.scrollLinks.setVisibility(View.GONE);
            } else {
                ui.scrollLinks.setVisibility(View.VISIBLE);
                try {
                    DisplayMetrics dm = act.getResources().getDisplayMetrics();
                    boolean isLand = dm.widthPixels > dm.heightPixels;
                    int maxPx = Math.round((isLand ? 128f : 250f) * dm.density);
                    int measureW = ui.dialogWidthPx > 0 ? Math.max(200, ui.dialogWidthPx - Math.round(36f * dm.density)) : Math.round(320f * dm.density);
                    ui.listContainer.measure(
                            View.MeasureSpec.makeMeasureSpec(measureW, View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    );
                    int measuredH = ui.listContainer.getMeasuredHeight();
                    ViewGroup.LayoutParams lp = ui.scrollLinks.getLayoutParams();
                    if (lp != null) {
                        int desiredH = measuredH > maxPx ? maxPx : ViewGroup.LayoutParams.WRAP_CONTENT;
                        if (lp.height != desiredH) {
                            lp.height = desiredH;
                            ui.scrollLinks.setLayoutParams(lp);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (ui.txtEmpty != null) {
            if (filtered.isEmpty()) {
                ui.txtEmpty.setVisibility(View.VISIBLE);
                if (!enabled) {
                    ui.txtEmpty.setText("گربر خاموش است. برای شناسایی لینک‌های شبکه دکمه «گربر: خاموش» را روشن کنید.");
                } else {
                    ui.txtEmpty.setText("در حال شنود ترافیک پخش برای شناسایی لینک‌های قابل دانلود...");
                }
            } else {
                ui.txtEmpty.setVisibility(View.GONE);
            }
        }

        if (ui.txtUrl != null) {
            String shown = !TextUtils.isEmpty(selectedUrl)
                    ? selectedUrl
                    : (enabled ? rawFallback : "");
            ui.txtUrl.setText(TextUtils.isEmpty(shown) ? "لینکی انتخاب نشده است" : shown);
        }
    }

    private static void updateFilterChip(Activity act, TextView chip, String text, boolean active, boolean visible) {
        if (chip == null) return;
        chip.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        chip.setText(text);
        int bgId = Res.drawable(act, active ? "stream_btn_primary_bg" : "stream_btn_secondary_bg");
        if (bgId != 0) chip.setBackgroundResource(bgId);
        chip.setTextColor(active ? 0xFFFFFFFF : Res.getColor(act, "TxtcolorHome", 0xFF212121));
    }

    private static String activeSessionUrl(Activity act, PlayerSession s) {
        synchronized (LOCK) {
            if (!TextUtils.isEmpty(s.selectedUrl)) {
                return s.selectedUrl;
            }
            List<GrabbedItem> items = getSortedSnapshotLocked(s);
            if (!items.isEmpty()) {
                return items.get(0).url;
            }
            return isGrabberEnabled(act) ? s.rawUrl : "";
        }
    }

    private static boolean isSelectedRedirectChecked(PlayerSession s, String url) {
        if (TextUtils.isEmpty(url)) return false;
        synchronized (LOCK) {
            GrabbedItem item = s.items.get(url);
            return item != null && item.redirectChecked;
        }
    }

    public static void onDownloadButtonClicked(final Activity act) {
        if (act == null || isActivityDead(act)) return;
        final PlayerSession s = getOrCreateSession(act);

        boolean enabled = isGrabberEnabled(act);
        boolean needResolve = false;
        String raw;
        String title;
        synchronized (LOCK) {
            raw = s.rawUrl;
            title = s.movieTitle;
            if (enabled && !hasPrimaryVideoOrStream(s) && !TextUtils.isEmpty(raw)) {
                needResolve = true;
            }
        }
        if (needResolve) {
            triggerFallbackResolve(act, s, raw);
        }

        int layoutId = Res.layout(act, "dialog_player_download");
        if (layoutId == 0) {
            String url = activeSessionUrl(act, s);
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
        int width = 0;
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics dm = act.getResources().getDisplayMetrics();
            int maxW = (int) (440f * dm.density);
            width = Math.min((int) (dm.widthPixels * 0.92f), maxW);
            win.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        TextView btnClose = (TextView) dialog.findViewById(Res.id(act, "BtnClose_StreamDlg"));
        TextView txtTitle = (TextView) dialog.findViewById(Res.id(act, "TxtMovieTitle_StreamDlg"));
        View btnOpen = dialog.findViewById(Res.id(act, "BtnOpenUrl_StreamDlg"));
        View btnCopy = dialog.findViewById(Res.id(act, "BtnCopyUrl_StreamDlg"));
        View btnAdm = dialog.findViewById(Res.id(act, "BtnAdmUrl_StreamDlg"));

        final DialogUi ui = new DialogUi();
        ui.dialogWidthPx = width;
        ui.btnToggle = (TextView) dialog.findViewById(Res.id(act, "BtnDialogGrabberToggle_StreamDlg"));
        ui.txtStatus = (TextView) dialog.findViewById(Res.id(act, "TxtStatus_StreamDlg"));
        ui.rowFilters = dialog.findViewById(Res.id(act, "RowFilters_StreamDlg"));
        ui.tabAll = (TextView) dialog.findViewById(Res.id(act, "TabFilterAll_StreamDlg"));
        ui.tabVideo = (TextView) dialog.findViewById(Res.id(act, "TabFilterVideo_StreamDlg"));
        ui.tabStream = (TextView) dialog.findViewById(Res.id(act, "TabFilterStream_StreamDlg"));
        ui.tabExtra = (TextView) dialog.findViewById(Res.id(act, "TabFilterExtra_StreamDlg"));
        ui.scrollLinks = dialog.findViewById(Res.id(act, "ScrollLinks_StreamDlg"));
        ui.listContainer = (LinearLayout) dialog.findViewById(Res.id(act, "ListLinks_StreamDlg"));
        ui.txtEmpty = (TextView) dialog.findViewById(Res.id(act, "TxtEmptyLinks_StreamDlg"));
        ui.txtUrl = (TextView) dialog.findViewById(Res.id(act, "TxtUrl_StreamDlg"));

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
            s.dialogUiRef = new WeakReference<DialogUi>(ui);
        }
        renderDialogState(act, s, ui);

        if (ui.btnToggle != null) {
            ui.btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleGrabberEnabled(act);
                }
            });
        }

        bindFilterClick(act, s, ui, ui.tabAll, "all");
        bindFilterClick(act, s, ui, ui.tabVideo, "video");
        bindFilterClick(act, s, ui, ui.tabStream, "stream");
        bindFilterClick(act, s, ui, ui.tabExtra, "extra");

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
                    String url = activeSessionUrl(act, s);
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
                    String url = activeSessionUrl(act, s);
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
                    String url = activeSessionUrl(act, s);
                    if (TextUtils.isEmpty(url)) {
                        Toast.makeText(act, "لینکی یافت نشد", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    if (!isSelectedRedirectChecked(s, url)) {
                        openAdmFromMenu(act, url);
                    } else {
                        launchAdmWithFallback(act, url);
                    }
                }
            });
        }

        dialog.show();
    }

    private static void bindFilterClick(
            final Activity act,
            final PlayerSession s,
            final DialogUi ui,
            TextView tab,
            final String filterKey) {
        if (tab == null) return;
        tab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                synchronized (LOCK) {
                    s.activeFilter = filterKey;
                }
                renderDialogState(act, s, ui);
            }
        });
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

    private static final class ResolveResult {
        final String finalUrl;
        final String contentType;
        final long sizeBytes;
        final String dispName;

        ResolveResult(String finalUrl, String contentType, long sizeBytes, String dispName) {
            this.finalUrl = finalUrl;
            this.contentType = contentType;
            this.sizeBytes = sizeBytes;
            this.dispName = dispName;
        }
    }

    public static String resolveFinalStreamUrl(String rawUrl) {
        return resolveFinalStreamWithMeta(rawUrl).finalUrl;
    }

    private static ResolveResult resolveFinalStreamWithMeta(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) return new ResolveResult(rawUrl, null, -1L, null);
        String current = rawUrl.trim();
        if (!isHttpUrl(current)) {
            return new ResolveResult(current, null, -1L, null);
        }

        String ua = null;
        try {
            ua = System.getProperty("http.agent");
        } catch (Throwable ignored) {}
        if (TextUtils.isEmpty(ua)) {
            ua = "Mymoviebox";
        }

        String lastCt = null;
        long lastSize = -1L;
        String lastDisp = null;

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
                    if (!isHttpUrl(nextStr)) {
                        break;
                    }
                    current = nextStr;
                    continue;
                }

                lastCt = conn.getHeaderField("Content-Type");
                lastDisp = parseContentDispositionFilename(conn.getHeaderField("Content-Disposition"));
                String cr = conn.getHeaderField("Content-Range");
                lastSize = parseContentRangeTotal(cr);
                if (lastSize <= 0) {
                    long cl = parsePositiveLong(conn.getHeaderField("Content-Length"));
                    if (cl > 1) lastSize = cl;
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
        return new ResolveResult(current, lastCt, lastSize, lastDisp);
    }

    private static String fetchSmallText(String urlStr) {
        if (!isHttpUrl(urlStr)) return null;
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) return null;
            in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < MAX_MANIFEST_BYTES) {
                bos.write(buf, 0, Math.min(n, MAX_MANIFEST_BYTES - bos.size()));
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {}
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.trim().toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean isAlwaysIgnoredResource(String url, String contentType, boolean hasPlaylist) {
        String path = stripQueryAndFragment(url).toLowerCase(Locale.US);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (ct.startsWith("image/")) return true;
        for (String ext : IMAGE_EXTS) {
            if (path.endsWith(ext)) return true;
        }
        for (String ext : HARD_IGNORED_SEGMENT_EXTS) {
            if (path.endsWith(ext)) return true;
        }
        if (hasPlaylist && (path.endsWith(".ts") || path.endsWith(".aac"))) {
            return true;
        }
        return false;
    }

    private static String classifyCategory(PlayerSession s, String url, String contentType, long sizeBytes) {
        String path = stripQueryAndFragment(url).toLowerCase(Locale.US);
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);

        if (ct.contains("mpegurl") || path.endsWith(".m3u8") || path.endsWith(".m3u")) {
            return "hls";
        }
        if (ct.contains("dash+xml") || path.endsWith(".mpd")) {
            return "dash";
        }
        if ((s != null && !TextUtils.isEmpty(s.subTitleUrl) && url.equalsIgnoreCase(s.subTitleUrl))
                || ct.contains("subrip") || ct.contains("text/vtt") || ct.contains("ttml")) {
            return "subtitle";
        }
        for (String ext : SUBTITLE_EXTS) {
            if (path.endsWith(ext)) return "subtitle";
        }
        if (ct.startsWith("audio/")) return "audio";
        for (String ext : AUDIO_EXTS) {
            if (path.endsWith(ext)) return "audio";
        }
        if (ct.startsWith("video/")) return "video";
        for (String ext : VIDEO_EXTS) {
            if (path.endsWith(ext)) return "video";
        }
        if (ct.contains("octet-stream") || sizeBytes > 65536L || (s != null && url.equals(s.rawUrl))) {
            return "video";
        }
        return "other";
    }

    private static String detectFormatLabel(String url, String contentType, String category) {
        String path = stripQueryAndFragment(url).toLowerCase(Locale.US);
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot + 1 < path.length()) {
            String ext = path.substring(dot + 1);
            if (ext.length() >= 2 && ext.length() <= 5 && !ext.equals("php") && !ext.equals("asp") && !ext.equals("aspx") && !ext.equals("html")) {
                return ext.toUpperCase(Locale.US);
            }
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        if (ct.contains("matroska")) return "MKV";
        if (ct.contains("mp4")) return "MP4";
        if (ct.contains("webm")) return "WEBM";
        if (ct.contains("mpegurl")) return "M3U8";
        if (ct.contains("dash+xml")) return "MPD";
        if (ct.contains("subrip")) return "SRT";
        if (ct.contains("vtt")) return "VTT";
        if ("hls".equals(category)) return "M3U8";
        if ("dash".equals(category)) return "MPD";
        if ("subtitle".equals(category)) return "SUB";
        if ("audio".equals(category)) return "AUDIO";
        if ("video".equals(category)) return "STREAM";
        return "LINK";
    }

    private static String formatCategoryBadge(String category, String formatLabel) {
        String label;
        if ("video".equals(category)) label = "ویدیو";
        else if ("hls".equals(category)) label = "HLS";
        else if ("dash".equals(category)) label = "DASH";
        else if ("audio".equals(category)) label = "صدا";
        else if ("subtitle".equals(category)) label = "زیرنویس";
        else label = "لینک";
        if (!TextUtils.isEmpty(formatLabel) && !formatLabel.equalsIgnoreCase(label)) {
            return label + " • " + formatLabel;
        }
        return label;
    }

    private static String extractQualityFromUrl(String url) {
        String path = stripQueryAndFragment(url);
        Matcher m = PAT_QUALITY_HINT.matcher(path);
        if (m.find()) {
            return m.group(1) + "p";
        }
        return "";
    }

    private static String extractDisplayName(String url) {
        String path = stripQueryAndFragment(url);
        int slash = path.lastIndexOf('/');
        String seg = slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
        if (TextUtils.isEmpty(seg)) {
            try {
                seg = new URL(url).getHost();
            } catch (Throwable ignored) {}
        }
        if (TextUtils.isEmpty(seg)) return url;
        return Uri.decode(seg);
    }

    private static String findHeader(Map<String, List<String>> hdrs, String name) {
        if (hdrs == null || hdrs.isEmpty() || TextUtils.isEmpty(name)) return null;
        for (Map.Entry<String, List<String>> e : hdrs.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                List<String> vals = e.getValue();
                if (vals != null && !vals.isEmpty()) {
                    return vals.get(0);
                }
            }
        }
        return null;
    }

    private static long parseTotalByteSize(Map<String, List<String>> hdrs, long openLen) {
        long cr = parseContentRangeTotal(findHeader(hdrs, "Content-Range"));
        if (cr > 0) return cr;
        long cl = parsePositiveLong(findHeader(hdrs, "Content-Length"));
        if (cl > 0) return cl;
        return openLen > 0 ? openLen : -1L;
    }

    private static long parseContentRangeTotal(String contentRange) {
        if (TextUtils.isEmpty(contentRange)) return -1L;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= contentRange.length()) return -1L;
        return parsePositiveLong(contentRange.substring(slash + 1).trim());
    }

    private static long parsePositiveLong(String s) {
        if (TextUtils.isEmpty(s)) return -1L;
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String parseContentDispositionFilename(String disp) {
        if (TextUtils.isEmpty(disp)) return null;
        int idx = disp.toLowerCase(Locale.US).indexOf("filename=");
        if (idx < 0) return null;
        String val = disp.substring(idx + 9).trim();
        int semi = val.indexOf(';');
        if (semi >= 0) val = val.substring(0, semi).trim();
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            val = val.substring(1, val.length() - 1);
        }
        return TextUtils.isEmpty(val) ? null : val;
    }

    private static String formatByteSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String formatBitrate(long bps) {
        if (bps >= 1000000L) {
            return String.format(Locale.US, "%.1f Mbps", bps / 1000000.0);
        }
        if (bps >= 1000L) {
            return (bps / 1000L) + " Kbps";
        }
        return bps + " bps";
    }

    private static String resolveRelativeUrl(String baseUrl, String rel) {
        try {
            return new URL(new URL(baseUrl), rel.trim()).toExternalForm();
        } catch (Throwable ignored) {
            return rel;
        }
    }

    private static String matchGroup1(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private static String buildMeta(String a, String b) {
        if (TextUtils.isEmpty(a) && TextUtils.isEmpty(b)) return "";
        if (TextUtils.isEmpty(a)) return b;
        if (TextUtils.isEmpty(b)) return a;
        if (a.equalsIgnoreCase(b)) return a;
        return a + " • " + b;
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
