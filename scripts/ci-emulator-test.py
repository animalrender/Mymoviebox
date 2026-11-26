#!/usr/bin/env python3
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path


PKG = "com.delfan.manNn"


def adb(*args, check=True, capture=True):
    cmd = ["adb", *args]
    res = subprocess.run(cmd, text=True, capture_output=capture)
    if check and res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed ({res.returncode}): {res.stderr}")
    return res.stdout if capture else ""


def adb_am_start(*am_args):
    return adb("shell", shlex.join(["am", "start", *am_args]))


def adb_bytes(*args):
    res = subprocess.run(["adb", *args], capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {res.stderr.decode('utf-8', 'replace')}")
    return res.stdout


def screencap(out_dir: Path, name: str):
    data = adb_bytes("exec-out", "screencap", "-p")
    path = out_dir / name
    path.write_bytes(data)
    print(f"  [screenshot] {path.name} ({len(data)} bytes)", flush=True)


def dump_ui(dismiss_anr: bool = True):
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
        xml_text = adb("shell", "cat", "/sdcard/uidump.xml")
        root = ET.fromstring(xml_text)
        if dismiss_anr:
            aerr_close = find_node(root, resource_id="android:id/aerr_close")
            if aerr_close is not None and aerr_close.attrib.get("package") == "android":
                print("  [system] dismissing Android ANR dialog (Close app)", flush=True)
                tap_node(aerr_close, "android:id/aerr_close", wait=1.0)
                continue
        return root
    return root


def parse_bounds(bounds_str: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1, y1, x2, y2)


def center_of(node):
    b = parse_bounds(node.attrib.get("bounds", ""))
    if not b:
        return None
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def find_node(root, *, resource_id=None, text=None, text_contains=None, content_desc=None, min_y=None, max_y=None):
    for node in root.iter("node"):
        rid = node.attrib.get("resource-id", "")
        txt = node.attrib.get("text", "")
        cd = node.attrib.get("content-desc", "")
        b = parse_bounds(node.attrib.get("bounds", ""))
        if resource_id and not (rid == resource_id or rid.endswith(":id/" + resource_id)):
            continue
        if text is not None and txt != text:
            continue
        if text_contains is not None and text_contains not in txt:
            continue
        if content_desc is not None and content_desc not in cd:
            continue
        if b is not None:
            cy = (b[1] + b[3]) // 2
            if min_y is not None and cy < min_y:
                continue
            if max_y is not None and cy > max_y:
                continue
        return node
    return None


def find_all_nodes(root, *, resource_id=None):
    out = []
    for node in root.iter("node"):
        rid = node.attrib.get("resource-id", "")
        if resource_id and not (rid == resource_id or rid.endswith(":id/" + resource_id)):
            continue
        out.append(node)
    return out


def list_all_texts(root):
    out = []
    for node in root.iter("node"):
        t = node.attrib.get("text", "").strip()
        cd = node.attrib.get("content-desc", "").strip()
        rid = node.attrib.get("resource-id", "").strip()
        b = node.attrib.get("bounds", "")
        if t or cd or rid:
            out.append(f"{rid} {b}: text={t!r} desc={cd!r}")
    return out


def tap_node(node, label="node", wait=1.0):
    if node is None:
        raise AssertionError(f"UI node not found for tap: {label}")
    coords = center_of(node)
    if not coords:
        raise AssertionError(f"Invalid bounds on {label}: {node.attrib}")
    adb("shell", "input", "tap", str(coords[0]), str(coords[1]))
    time.sleep(wait)


def wait_for_node(timeout=15.0, **kwargs):
    deadline = time.time() + timeout
    last_root = None
    while time.time() < deadline:
        try:
            root = dump_ui()
            last_root = root
            n = find_node(root, **kwargs)
            if n is not None:
                return root, n
        except Exception:
            pass
        time.sleep(0.8)
    texts = list_all_texts(last_root) if last_root is not None else []
    raise AssertionError(f"Timed out waiting for node {kwargs}. Visible UI:\n" + "\n".join(texts[:45]))


def assert_text_absent(root, needle: str):
    for node in root.iter("node"):
        if needle in node.attrib.get("text", ""):
            raise AssertionError(f"Unexpected text {needle!r} present in UI: {node.attrib}")


def enter_text_in_field(node, value: str):
    tap_node(node, "EditText", wait=0.5)
    adb("shell", "input", "text", value)
    time.sleep(0.5)


def dismiss_app_popups(root):
    for _ in range(3):
        if root is None:
            return root
        retry_btn = find_node(root, resource_id="LinRload_ActSplash") or find_node(root, text="بارگذاری مجدد")
        if retry_btn is not None:
            tap_node(retry_btn, "retry splash connection", wait=3.0)
            root = dump_ui()
            continue
        ok_btn = (
            find_node(root, resource_id="PopupShowDisConnect_BtnOk")
            or find_node(root, resource_id="PopupShowMsgVW_BtnOk")
            or find_node(root, resource_id="PopupShowMsg_BtnOk")
            or find_node(root, text="باشه")
        )
        if ok_btn is not None:
            tap_node(ok_btn, "dismiss popup", wait=1.2)
            root = dump_ui()
            continue
        break
    return root


def wait_for_main_settled(timeout=45.0, wait_vitrin_sec=8.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            root = dismiss_app_popups(dump_ui())
            if find_node(root, resource_id="bottom_navigation") is not None:
                # Wait briefly for async WebView welcome popup (msgid) that opens right after activity_main starts
                time.sleep(1.5)
                root = dismiss_app_popups(dump_ui())
                if find_node(root, resource_id="bottom_navigation") is not None:
                    if wait_vitrin_sec > 0:
                        v_end = time.time() + wait_vitrin_sec
                        while time.time() < v_end:
                            root = dismiss_app_popups(dump_ui())
                            if find_node(root, resource_id="RelMain_ItemSeryalHome") is not None:
                                break
                            time.sleep(1.0)
                    return root
        except Exception:
            pass
        time.sleep(1.0)
    raise AssertionError("Timed out waiting for activity_main bottom_navigation")


def launch_and_dismiss_to_main():
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/com.delfan.madp.Acts.activity_splash")
    return wait_for_main_settled(timeout=45.0)


def tap_bottom_tab(label_contains: str):
    root = dismiss_app_popups(dump_ui())
    nav = find_node(root, resource_id="bottom_navigation")
    if nav is None:
        root = wait_for_main_settled(timeout=15.0)
        nav = find_node(root, resource_id="bottom_navigation")
    min_y = 1900
    if nav is not None:
        nb = parse_bounds(nav.attrib.get("bounds", ""))
        if nb is not None:
            min_y = nb[1]
    tab = (
        find_node(root, text_contains=label_contains, min_y=min_y)
        or find_node(root, content_desc=label_contains, min_y=min_y)
    )
    tap_node(tab, f"bottom tab {label_contains}", wait=1.5)
    return dismiss_app_popups(dump_ui())


def card_title(card_node) -> str:
    for sub in card_node.iter("node"):
        rid = sub.attrib.get("resource-id", "")
        if rid.endswith(":id/TxtTitle_ItemSeryalHome"):
            return sub.attrib.get("text", "").strip()
    return ""


def card_imdb(card_node) -> str:
    for sub in card_node.iter("node"):
        rid = sub.attrib.get("resource-id", "")
        if rid.endswith(":id/TxtImdb_ItemSeryalHome"):
            return sub.attrib.get("text", "").strip()
    return ""


def measure_cards_layout(root):
    cards = []
    for c in find_all_nodes(root, resource_id="RelMain_ItemSeryalHome"):
        title = card_title(c)
        b = parse_bounds(c.attrib.get("bounds", ""))
        if title and b is not None:
            x1, y1, x2, y2 = b
            cards.append({
                "title": title,
                "imdb": card_imdb(c),
                "x1": x1,
                "y1": y1,
                "x2": x2,
                "y2": y2,
                "width": x2 - x1,
                "node": c,
            })
    return cards


def wait_for_main_after_reload(timeout=45.0):
    return wait_for_main_settled(timeout=timeout, wait_vitrin_sec=0.0)


def _nudge_list_refresh(root):
    for rid in ("PopupShowDisConnect_BtnOk", "LinRload_ActSplash"):
        btn = find_node(root, resource_id=rid)
        if btn is not None:
            tap_node(btn, f"retry {rid}", wait=2.0)
            return
    for label in ("بارگذاری مجدد", "تلاش مجدد"):
        btn = find_node(root, text_contains=label)
        if btn is not None:
            tap_node(btn, f"retry {label}", wait=2.0)
            return
    adb("shell", "input", "swipe", "540", "700", "540", "1500", "350")
    time.sleep(1.5)


def wait_for_cinema_cards(tab_label="سینمایی", min_count=3, timeout=65.0):
    tap_bottom_tab(tab_label)
    start = time.time()
    deadline = start + timeout
    cards = []
    root = None
    nudged = False
    while time.time() < deadline:
        root = dismiss_app_popups(dump_ui())
        cards = measure_cards_layout(root)
        if len(cards) >= min_count:
            return root, cards
        if not nudged and (time.time() - start) >= 25.0:
            nudged = True
            print(f"  [retry] {tab_label} still loading after 25s; triggering refresh", flush=True)
            _nudge_list_refresh(root)
        time.sleep(1.0)
    raise AssertionError(
        f"Timed out waiting for {min_count} cards on {tab_label!r}. Visible:\n"
        + "\n".join(list_all_texts(root)[:35] if root is not None else [])
    )


def open_drawer_from_cinema():
    root, btn = wait_for_node(timeout=8.0, resource_id="RelIcMenu_ToolBar_FrCinema")
    tap_node(btn, "RelIcMenu_ToolBar_FrCinema", wait=1.0)
    wait_for_node(timeout=6.0, resource_id="DrMenu_TxtName")
    item = None
    for _ in range(5):
        root = dump_ui()
        cand = find_node(root, resource_id="DrMenu_RelChangeMovieTitle")
        if cand is not None:
            b = parse_bounds(cand.attrib.get("bounds", ""))
            if b is not None and (b[3] - b[1]) >= 40 and b[3] <= 2280:
                item = cand
                break
        # Scroll inside the right drawer (x around 650) toward the bottom
        adb("shell", "input", "swipe", "650", "1550", "650", "650", "300")
        time.sleep(0.8)
    if item is None:
        root, item = wait_for_node(timeout=4.0, resource_id="DrMenu_RelChangeMovieTitle")
    txt_node = find_node(root, resource_id="DrMenu_TxtChangeMovieTitle")
    label = txt_node.attrib.get("text", "").strip() if txt_node is not None else ""
    return root, item, label


def wait_for_details_loaded(timeout=15.0):
    deadline = time.time() + timeout
    last_root = None
    while time.time() < deadline:
        try:
            root = dump_ui()
            last_root = root
            btn = find_node(root, resource_id="BtnAddFave_ADV")
            des = find_node(root, resource_id="TxtDes_ActDetialsVideo")
            des_txt = des.attrib.get("text", "").strip() if des is not None else ""
            if btn is not None and len(des_txt) > 5 and des_txt != "..." and "در حال دریافت" not in des_txt:
                time.sleep(0.3)
                fresh_root = dump_ui()
                fresh_btn = find_node(fresh_root, resource_id="BtnAddFave_ADV") or btn
                return fresh_root, fresh_btn
        except Exception:
            pass
        time.sleep(0.8)
    if last_root is not None:
        btn = find_node(last_root, resource_id="BtnAddFave_ADV")
        if btn is not None:
            return last_root, btn
    raise AssertionError("Timed out waiting for Details page to load")


def open_media_card(tab_label: str, index: int = 0) -> str:
    tap_bottom_tab(tab_label)
    start = time.time()
    deadline = start + 50.0
    cards = []
    root = None
    nudged = False
    while time.time() < deadline:
        root = dismiss_app_popups(dump_ui())
        cards = [
            c for c in find_all_nodes(root, resource_id="RelMain_ItemSeryalHome")
            if card_title(c)
        ]
        if len(cards) > index:
            break
        if not nudged and (time.time() - start) >= 20.0:
            nudged = True
            adb("shell", "input", "swipe", "540", "700", "540", "1500", "350")
            time.sleep(1.5)
        time.sleep(1.0)
    if len(cards) <= index:
        raise AssertionError(f"Could not find card #{index} under tab {tab_label!r}. Visible:\n" + "\n".join(list_all_texts(root)[:35]))
    title = card_title(cards[index])
    tap_node(cards[index], f"{tab_label} card #{index} ({title})", wait=2.0)
    return title


def _generate_sample_mp4(dest: Path):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError("ffmpeg not found in PATH")
    subprocess.run(
        [
            ffmpeg, "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=0x1565C0:s=320x180:r=15:d=8",
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono",
            "-t", "8",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline",
            "-c:a", "aac", "-movflags", "+faststart",
            str(dest),
        ],
        check=True,
    )


def _start_redirect_server(mp4_bytes: bytes, port: int = 18080):
    hits = []
    target_path = "/movies17/movies/2026/Sample.Movie.2026.720p.WEB-DL.mkv"
    target_query = "expire=1795617009&hash=TESTHASH123"
    expected_final_url = f"http://127.0.0.1:{port}{target_path}?{target_query}"
    twohop_final_url = f"http://127.0.0.1:{port}{target_path}?expire=1795617009&hash=TWOHOP999"
    fallback_final_url = f"http://127.0.0.1:{port}/cdn/Fallback.Direct.2026.mkv?token=FB503"
    hls_master_url = f"http://127.0.0.1:{port}/hls/master.m3u8?token=HLS302"
    hls_1080_url = f"http://127.0.0.1:{port}/hls/1080p/stream.m3u8?q=1080"

    master_m3u8 = (
        "#EXTM3U\n"
        '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio_en.m3u8"\n'
        '#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Persian",LANGUAGE="fa",URI="/subs/hls_fa.vtt"\n'
        "#EXT-X-STREAM-INF:BANDWIDTH=4200000,RESOLUTION=1920x1080\n"
        "1080p/stream.m3u8?q=1080\n"
        "#EXT-X-STREAM-INF:BANDWIDTH=2100000,RESOLUTION=1280x720\n"
        "720p/stream.m3u8?q=720\n"
    ).encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def do_GET(self):
            hits.append(("GET", self.path, self.headers.get("Range", "")))
            if self.path.startswith("/play-link/play.php"):
                self.send_response(302)
                self.send_header("Location", expected_final_url)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/play-link/twohop.php"):
                self.send_response(302)
                self.send_header("Location", "/hop2/redirect?id=99")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/hop2/redirect"):
                self.send_response(307)
                self.send_header("Location", twohop_final_url)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/play-link/hls-redirect.php"):
                self.send_response(302)
                self.send_header("Location", hls_master_url)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/hls/master.m3u8"):
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.apple.mpegurl")
                self.send_header("Content-Length", str(len(master_m3u8)))
                self.end_headers()
                self.wfile.write(master_m3u8)
                return
            if self.path.startswith("/play-link/adm-fallback.php"):
                self.send_response(302)
                self.send_header("Location", fallback_final_url)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/cdn/Fallback.Direct"):
                self.send_response(503)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if self.path.startswith("/subs/"):
                srt = b"1\r\n00:00:00,000 --> 00:00:05,000\r\nTest Subtitle Line\r\n\r\n"
                self.send_response(200)
                self.send_header("Content-Type", "application/x-subrip; charset=utf-8")
                self.send_header("Content-Length", str(len(srt)))
                self.end_headers()
                self.wfile.write(srt)
                return
            if self.path.startswith(target_path):
                total = len(mp4_bytes)
                rng = self.headers.get("Range")
                if rng and rng.startswith("bytes="):
                    spec = rng[len("bytes="):].split(",")[0].strip()
                    s_part, e_part = spec.split("-", 1)
                    start = int(s_part) if s_part else 0
                    end = int(e_part) if e_part else total - 1
                    end = min(end, total - 1)
                    if start > end or start >= total:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{total}")
                        self.end_headers()
                        return
                    chunk = mp4_bytes[start : end + 1]
                    self.send_response(206)
                    self.send_header("Content-Type", "video/mp4")
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
                    self.send_header("Content-Length", str(len(chunk)))
                    self.end_headers()
                    self.wfile.write(chunk)
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "video/mp4")
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Length", str(total))
                    self.end_headers()
                    self.wfile.write(mp4_bytes)
                return
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    th = threading.Thread(target=srv.serve_forever, daemon=True)
    th.start()
    return srv, expected_final_url, twohop_final_url, fallback_final_url, hls_master_url, hls_1080_url, hits


def _build_dummy_adm_apk(work_dir: Path) -> Path:
    sdk_roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        "/usr/local/lib/android/sdk",
        str(Path.home() / "Android/Sdk"),
    ]
    sdk = next((Path(p) for p in sdk_roots if p and Path(p).is_dir()), None)
    if sdk is None:
        raise RuntimeError("Android SDK not found for building dummy com.dv.adm test APK")
    android_jar = next((p / "android.jar" for p in sorted((sdk / "platforms").glob("android-*"), reverse=True) if (p / "android.jar").is_file()), None)
    bt = next((b for b in sorted((sdk / "build-tools").glob("*"), reverse=True) if (b / "aapt2").is_file()), None)
    if not android_jar or not bt:
        raise RuntimeError("android.jar or aapt2/d8/apksigner missing in SDK")

    work_dir.mkdir(parents=True, exist_ok=True)
    manifest = work_dir / "AndroidManifest.xml"
    manifest.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    package="com.dv.adm"\n'
        '    android:versionCode="1"\n'
        '    android:versionName="1.0">\n'
        '    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="33" />\n'
        '    <application android:label="ADM Test Receiver">\n'
        '        <activity android:name=".MainActivity" android:exported="true">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.VIEW" />\n'
        '                <category android:name="android.intent.category.DEFAULT" />\n'
        '                <category android:name="android.intent.category.BROWSABLE" />\n'
        '                <data android:scheme="http" />\n'
        '                <data android:scheme="https" />\n'
        '            </intent-filter>\n'
        '        </activity>\n'
        '    </application>\n'
        '</manifest>\n',
        encoding="utf-8",
    )

    java_src = work_dir / "src/com/dv/adm/MainActivity.java"
    java_src.parent.mkdir(parents=True, exist_ok=True)
    java_src.write_text(
        'package com.dv.adm;\n'
        'import android.app.Activity;\n'
        'import android.content.ClipData;\n'
        'import android.content.ClipboardManager;\n'
        'import android.content.Context;\n'
        'import android.graphics.Color;\n'
        'import android.os.Bundle;\n'
        'import android.util.Log;\n'
        'import android.widget.LinearLayout;\n'
        'import android.widget.TextView;\n'
        'public class MainActivity extends Activity {\n'
        '    private TextView clipView;\n'
        '    @Override protected void onCreate(Bundle b) {\n'
        '        super.onCreate(b);\n'
        '        LinearLayout root = new LinearLayout(this);\n'
        '        root.setOrientation(LinearLayout.VERTICAL);\n'
        '        root.setBackgroundColor(Color.BLACK);\n'
        '        root.setPadding(40, 80, 40, 40);\n'
        '        String uri = getIntent() != null ? String.valueOf(getIntent().getDataString()) : "";\n'
        '        Log.i("ADM_RECEIVED_URI", uri);\n'
        '        TextView tv = new TextView(this);\n'
        '        tv.setTextColor(Color.GREEN);\n'
        '        tv.setTextSize(16f);\n'
        '        tv.setText("ADM_RECEIVED_URI:" + uri);\n'
        '        root.addView(tv);\n'
        '        clipView = new TextView(this);\n'
        '        clipView.setTextColor(Color.WHITE);\n'
        '        clipView.setTextSize(14f);\n'
        '        clipView.setPadding(0, 32, 0, 0);\n'
        '        root.addView(clipView);\n'
        '        setContentView(root);\n'
        '    }\n'
        '    @Override public void onWindowFocusChanged(boolean hasFocus) {\n'
        '        super.onWindowFocusChanged(hasFocus);\n'
        '        if (!hasFocus || clipView == null) return;\n'
        '        String clipText = "";\n'
        '        try {\n'
        '            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);\n'
        '            if (cm != null && cm.hasPrimaryClip()) {\n'
        '                ClipData cd = cm.getPrimaryClip();\n'
        '                if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {\n'
        '                    clipText = cd.getItemAt(0).getText().toString();\n'
        '                }\n'
        '            }\n'
        '        } catch (Throwable ignored) {}\n'
        '        Log.i("ADM_CLIPBOARD_TEXT", clipText);\n'
        '        clipView.setText("CLIPBOARD_TEXT:" + clipText);\n'
        '    }\n'
        '}\n',
        encoding="utf-8",
    )

    cls_dir = work_dir / "classes"
    dex_dir = work_dir / "dex"
    cls_dir.mkdir(parents=True, exist_ok=True)
    dex_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [shutil.which("javac"), "-source", "1.8", "-target", "1.8", "-classpath", str(android_jar), "-d", str(cls_dir), str(java_src)],
        check=True,
    )
    subprocess.run(
        [str(bt / "d8"), "--min-api", "21", "--lib", str(android_jar), "--output", str(dex_dir), str(cls_dir / "com/dv/adm/MainActivity.class")],
        check=True,
    )
    raw_apk = work_dir / "raw.apk"
    subprocess.run(
        [str(bt / "aapt2"), "link", "-I", str(android_jar), "--manifest", str(manifest), "-o", str(raw_apk)],
        check=True,
    )
    import zipfile
    with zipfile.ZipFile(raw_apk, "a") as zf:
        zf.write(dex_dir / "classes.dex", "classes.dex")
    aligned = work_dir / "aligned.apk"
    signed = work_dir / "dummy-adm.apk"
    ks = work_dir / "dummy.jks"
    subprocess.run(
        [
            shutil.which("keytool"), "-genkeypair", "-keystore", str(ks), "-alias", "adm",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
            "-storepass", "android", "-keypass", "android", "-dname", "CN=ADM Test",
        ],
        check=True,
        capture_output=True,
    )
    subprocess.run([str(bt / "zipalign"), "-f", "-p", "4", str(raw_apk), str(aligned)], check=True)
    subprocess.run(
        [
            str(bt / "apksigner"), "sign", "--ks", str(ks), "--ks-pass", "pass:android",
            "--ks-key-alias", "adm", "--key-pass", "pass:android", "--out", str(signed), str(aligned),
        ],
        check=True,
    )
    return signed


def _screen_center(root=None):
    if root is not None:
        pv = find_node(root, resource_id="player_view")
        if pv is not None:
            c = center_of(pv)
            if c is not None:
                return c
        for child in root.findall("node"):
            c = center_of(child)
            if c is not None:
                return c
    out = adb("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        return 540, 1100
    w, h = int(m.group(1)), int(m.group(2))
    return w // 2, h // 2


def _dismiss_player_overlays(root):
    if root is None:
        return False
    for rid, pkg in (("android:id/ok", "android"), ("android:id/ok", "com.android.systemui")):
        ok = find_node(root, resource_id=rid)
        if ok is not None and ok.attrib.get("package") == pkg:
            b = parse_bounds(ok.attrib.get("bounds", ""))
            if b is not None and (b[2] - b[0]) > 10:
                tap_node(ok, f"dismiss system cling ({rid})", wait=0.8)
                return True
    if find_node(root, resource_id="android:id/immersive_cling_chevron") is not None:
        for node in root.iter("node"):
            if node.attrib.get("package") == "android" and node.attrib.get("clickable") == "true":
                b = parse_bounds(node.attrib.get("bounds", ""))
                if b is not None and (b[2] - b[0]) > 10:
                    tap_node(node, "dismiss immersive cling button", wait=0.8)
                    return True
    help_ok = find_node(root, resource_id="LinBtnHelpBright") or find_node(root, text="متوجه شدم")
    if help_ok is not None:
        b = parse_bounds(help_ok.attrib.get("bounds", ""))
        if b is not None and (b[2] - b[0]) > 10:
            tap_node(help_ok, "dismiss LinBtnHelpBright", wait=0.8)
            return True
    return False


def _ensure_player_controls_visible(timeout: float = 20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        root = dump_ui()
        if _dismiss_player_overlays(root):
            continue
        btn = find_node(root, resource_id="btn_download_stream")
        if btn is not None:
            b = parse_bounds(btn.attrib.get("bounds", ""))
            if b is not None and (b[2] - b[0]) > 10 and (b[3] - b[1]) > 10:
                return root, btn
        cx, cy = _screen_center(root)
        adb("shell", "input", "tap", str(cx), str(cy))
        time.sleep(0.5)
    raise AssertionError("Timed out waiting for player controller btn_download_stream")


def _wait_for_stream_grabbed(timeout: float = 30.0):
    deadline = time.time() + timeout
    last_desc = None
    while time.time() < deadline:
        rem = max(12.0, deadline - time.time())
        root, btn = _ensure_player_controls_visible(timeout=rem)
        cd = btn.attrib.get("content-desc", "")
        last_desc = cd
        if "آماده" in cd:
            return root, btn
        time.sleep(0.7)
    raise AssertionError(f"Timed out waiting for btn_download_stream filled/captured state. Last desc={last_desc!r}")


def _open_stream_dialog():
    for attempt in range(3):
        _, btn = _ensure_player_controls_visible(timeout=20.0)
        tap_node(btn, f"btn_download_stream (attempt {attempt + 1})", wait=0.8)
        try:
            return wait_for_node(timeout=8.0, resource_id="TxtUrl_StreamDlg")
        except AssertionError:
            pass
    raise AssertionError("Timed out waiting for TxtUrl_StreamDlg after tapping btn_download_stream")


def _boxes_overlap(a, b):
    if not a or not b:
        return False
    return (a[2] > b[0]) and (a[0] < b[2]) and (a[3] > b[1]) and (a[1] < b[3])


def _check_player_header_geometry(root, dl_btn_node, is_land, log):
    dl_b = parse_bounds(dl_btn_node.attrib.get("bounds", ""))
    toggle_node = find_node(root, resource_id="btn_grabber_toggle")
    if toggle_node is None:
        raise AssertionError("Missing btn_grabber_toggle in player controller header!")
    toggle_b = parse_bounds(toggle_node.attrib.get("bounds", ""))
    if not toggle_b or (toggle_b[2] - toggle_b[0]) < 20 or (toggle_b[3] - toggle_b[1]) < 20:
        raise AssertionError(f"btn_grabber_toggle invalid bounds: {toggle_b}")
    if toggle_b[2] > dl_b[0]:
        raise AssertionError(f"btn_grabber_toggle {toggle_b} should be left of btn_download_stream {dl_b}")

    back_node = find_node(root, resource_id="btn_back")
    back_b = parse_bounds(back_node.attrib.get("bounds", "")) if back_node is not None else None
    if back_b and _boxes_overlap(back_b, toggle_b):
        raise AssertionError(f"btn_back {back_b} overlaps btn_grabber_toggle {toggle_b} (is_land={is_land})!")

    sub_node = find_node(root, resource_id="btn_subtitles")
    sub_b = parse_bounds(sub_node.attrib.get("bounds", "")) if sub_node is not None else None
    if sub_b and (sub_b[2] - sub_b[0]) > 0 and _boxes_overlap(dl_b, sub_b):
        raise AssertionError(f"btn_download_stream {dl_b} overlaps btn_subtitles {sub_b} (is_land={is_land})!")

    title_node = find_node(root, resource_id="TxtTitleFilm")
    title_bounds = parse_bounds(title_node.attrib.get("bounds", "")) if title_node is not None else None
    if is_land and title_bounds and (title_bounds[2] - title_bounds[0]) > 0 and _boxes_overlap(title_bounds, toggle_b):
        raise AssertionError(f"Landscape TxtTitleFilm {title_bounds} overlaps btn_grabber_toggle {toggle_b}!")

    mode = "landscape" if is_land else "portrait"
    log(f"  Header layout verified ({mode}, no overlap): back={back_b}, toggle={toggle_b}, dl={dl_b}, sub={sub_b}, title={title_bounds}")


def run_player_and_adm_e2e(scr_dir: Path, log_dir: Path, log):
    port = 18080
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        mp4_path = tmp_dir / "sample.mp4"
        _generate_sample_mp4(mp4_path)
        mp4_bytes = mp4_path.read_bytes()
        log(f"  Generated sample MP4 ({len(mp4_bytes)} bytes)")

        srv, expected_url, twohop_url, fallback_url, hls_master_url, hls_1080_url, hits = _start_redirect_server(mp4_bytes, port=port)
        dummy_adm_installed = False
        try:
            adb("reverse", f"tcp:{port}", f"tcp:{port}")
            adb("shell", "pm", "uninstall", "com.dv.adm", check=False)

            raw_play_url = f"http://127.0.0.1:{port}/play-link/play.php?t=movie&i=72818&m=09330000000&ap=Delfan"
            sub_url = f"http://127.0.0.1:{port}/subs/test.srt"
            movie_title = "Test Redirect Movie 2026 Long Title Check"

            adb("shell", "am", "force-stop", PKG)
            time.sleep(0.5)
            adb_am_start(
                "-W",
                "-n", f"{PKG}/com.delfan.madp.Acts.activity_video_player",
                "--es", "url", raw_play_url,
                "--es", "sub_title", sub_url,
                "--es", "title", movie_title,
                "--es", "Type_movie", "1",
                "--es", "Link_Id", "72818",
            )
            time.sleep(2.5)

            root1, btn1 = _wait_for_stream_grabbed(timeout=30.0)
            rot1 = root1.attrib.get("rotation", "0")
            is_land1 = rot1 in ("1", "3")
            _check_player_header_geometry(root1, btn1, is_land1, log)
            btn_toggle1 = find_node(root1, resource_id="btn_grabber_toggle")
            if "روشن" not in (btn_toggle1.attrib.get("content-desc", "")):
                raise AssertionError(f"Expected btn_grabber_toggle ON initially, got {btn_toggle1.attrib}")
            screencap(scr_dir, "23-player-initial-grabbed.png")
            log(f"  Initial player grabbed stream (rotation={rot1}): btn desc={btn1.attrib.get('content-desc')!r}")

            # Open stream grabber dialog & check multi-link capture (video + .srt subtitle), filter chips, toggle pill
            root_dlg, tv_url = _open_stream_dialog()
            deadline_multi = time.time() + 10.0
            while find_node(root_dlg, text_contains="صدا / زیرنویس (1)") is None and time.time() < deadline_multi:
                time.sleep(0.7)
                root_dlg = dump_ui()
                tv_url = find_node(root_dlg, resource_id="TxtUrl_StreamDlg") or tv_url
            shown_url = tv_url.attrib.get("text", "").strip()
            if shown_url != expected_url:
                raise AssertionError(f"Expected redirected CDN URL {expected_url!r}, got {shown_url!r}")
            dlg_title_node = find_node(root_dlg, resource_id="TxtMovieTitle_StreamDlg")
            dlg_title = dlg_title_node.attrib.get("text", "").strip() if dlg_title_node is not None else ""
            if movie_title not in dlg_title:
                raise AssertionError(f"Expected movie title {movie_title!r} in dialog, got {dlg_title!r}")
            if find_node(root_dlg, text_contains="Sample.Movie.2026.720p.WEB-DL.mkv") is None:
                raise AssertionError("Multi-link list missing redirected video card Sample.Movie.2026.720p.WEB-DL.mkv")
            if find_node(root_dlg, text_contains="صدا / زیرنویس (1)") is None:
                raise AssertionError("Multi-link filter missing subtitle count 'صدا / زیرنویس (1)'")
            if find_node(root_dlg, resource_id="BtnDialogGrabberToggle_StreamDlg") is None:
                raise AssertionError("Missing BtnDialogGrabberToggle_StreamDlg in dialog")
            for action_id in ("BtnOpenUrl_StreamDlg", "BtnCopyUrl_StreamDlg", "BtnAdmUrl_StreamDlg"):
                an = find_node(root_dlg, resource_id=action_id)
                ab = parse_bounds(an.attrib.get("bounds", "")) if an is not None else None
                if an is None or not ab or (ab[3] - ab[1]) < 30:
                    raise AssertionError(f"Missing or clipped action button {action_id} in stream download dialog (bounds={ab})")
            screencap(scr_dir, "24-player-download-dialog.png")

            # Test category filter chip & user selection of subtitle link
            tap_node(find_node(root_dlg, resource_id="TabFilterExtra_StreamDlg"), "TabFilterExtra_StreamDlg", wait=0.6)
            root_sub_tab = dump_ui()
            list_sub_tab = find_node(root_sub_tab, resource_id="ListLinks_StreamDlg")
            if list_sub_tab is None:
                raise AssertionError("Missing ListLinks_StreamDlg under Extra filter tab")
            assert_text_absent(list_sub_tab, "Sample.Movie.2026.720p.WEB-DL.mkv")
            sub_card = find_node(root_sub_tab, text_contains="test.srt")
            if sub_card is None:
                raise AssertionError("Subtitle item test.srt missing under Extra filter tab")
            tap_node(sub_card, "select test.srt link", wait=0.6)
            root_sub_sel = dump_ui()
            sel_sub_url = find_node(root_sub_sel, resource_id="TxtUrl_StreamDlg").attrib.get("text", "").strip()
            if sel_sub_url != sub_url:
                raise AssertionError(f"Expected TxtUrl_StreamDlg to update to {sub_url!r}, got {sel_sub_url!r}")

            # Switch back to All filter & select video link before ADM test
            tap_node(find_node(root_sub_sel, resource_id="TabFilterAll_StreamDlg"), "TabFilterAll_StreamDlg", wait=0.6)
            root_all_tab = dump_ui()
            vid_card = find_node(root_all_tab, text_contains="Sample.Movie.2026.720p.WEB-DL.mkv")
            tap_node(vid_card, "re-select primary video link", wait=0.6)
            root_dlg_sel = dump_ui()

            # Test ADM missing dialog (while com.dv.adm is NOT installed)
            tap_node(find_node(root_dlg_sel, resource_id="BtnAdmUrl_StreamDlg"), "BtnAdmUrl_StreamDlg (no ADM)", wait=1.2)
            root_adm_err, _ = wait_for_node(timeout=6.0, text_contains="برای دانلود با دانلودر ADM")
            screencap(scr_dir, "25-adm-missing-dialog.png")
            tap_node(find_node(root_adm_err, text="لغو"), "dismiss ADM install prompt", wait=0.8)

            # Toggle orientation via btn_screen_rotate & verify the other orientation header non-overlap
            root_ctl, _ = _ensure_player_controls_visible(timeout=20.0)
            rot_btn = find_node(root_ctl, resource_id="btn_screen_rotate")
            tap_node(rot_btn, "btn_screen_rotate -> toggle orientation", wait=2.5)
            root2, btn2 = _wait_for_stream_grabbed(timeout=30.0)
            rot2 = root2.attrib.get("rotation", "0")
            is_land2 = rot2 in ("1", "3")
            _check_player_header_geometry(root2, btn2, is_land2, log)
            if not (is_land1 or is_land2):
                raise AssertionError(f"Neither initial ({rot1}) nor toggled ({rot2}) orientation was landscape!")
            screencap(scr_dir, "26-player-rotated-grabbed.png")
            log(f"  Rotated orientation verified (rotation={rot1} -> {rot2})")

            # Build & install dummy com.dv.adm receiver to verify Copy + ADM Intent URI end-to-end
            dummy_apk = _build_dummy_adm_apk(tmp_dir / "adm-apk")
            adb("install", "-r", str(dummy_apk))
            dummy_adm_installed = True

            # Verify picking link #2 (subtitle .srt) and sending it to ADM sends sub_url!
            root_dlg_sub2, _ = _open_stream_dialog()
            tap_node(find_node(root_dlg_sub2, resource_id="TabFilterExtra_StreamDlg"), "TabFilterExtra_StreamDlg (ADM sub pick)", wait=0.6)
            root_dlg_sub2b = dump_ui()
            sub_card2 = find_node(root_dlg_sub2b, text_contains="test.srt")
            tap_node(sub_card2, "pick test.srt for ADM", wait=0.6)
            root_dlg_sub3 = dump_ui()
            tap_node(find_node(root_dlg_sub3, resource_id="BtnAdmUrl_StreamDlg"), "BtnAdmUrl_StreamDlg (subtitle selected)", wait=2.0)
            root_adm_sub, _ = wait_for_node(timeout=8.0, text_contains="ADM_RECEIVED_URI:")
            adm_sub_uri = find_node(root_adm_sub, text_contains="ADM_RECEIVED_URI:").attrib.get("text", "").split("ADM_RECEIVED_URI:", 1)[1].strip()
            if adm_sub_uri != sub_url:
                raise AssertionError(f"Expected ADM to receive selected subtitle URL {sub_url!r}, got {adm_sub_uri!r}")
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)

            # Open dialog -> pick video URL -> Copy -> Re-open -> Send to ADM
            root_dlg2, tv_url2 = _open_stream_dialog()
            tap_all2 = find_node(root_dlg2, resource_id="TabFilterAll_StreamDlg")
            if tap_all2 is not None:
                tap_node(tap_all2, "TabFilterAll_StreamDlg", wait=0.5)
                root_dlg2 = dump_ui()
            vid_card2 = find_node(root_dlg2, text_contains="Sample.Movie.2026.720p.WEB-DL.mkv")
            if vid_card2 is not None:
                tap_node(vid_card2, "select primary video link", wait=0.5)
                root_dlg2 = dump_ui()
            tv_url2 = find_node(root_dlg2, resource_id="TxtUrl_StreamDlg")
            shown_url2 = tv_url2.attrib.get("text", "").strip()
            if shown_url2 != expected_url:
                raise AssertionError(f"Rotated dialog URL mismatch: {shown_url2!r}")
            tap_node(find_node(root_dlg2, resource_id="BtnCopyUrl_StreamDlg"), "BtnCopyUrl_StreamDlg", wait=0.8)

            root_dlg3, _ = _open_stream_dialog()
            tap_node(find_node(root_dlg3, resource_id="BtnAdmUrl_StreamDlg"), "BtnAdmUrl_StreamDlg (with ADM)", wait=2.0)

            root_adm, _ = wait_for_node(timeout=8.0, text_contains="ADM_RECEIVED_URI:")
            adm_node = find_node(root_adm, text_contains="ADM_RECEIVED_URI:")
            clip_node = find_node(root_adm, text_contains="CLIPBOARD_TEXT:")
            adm_uri = adm_node.attrib.get("text", "").split("ADM_RECEIVED_URI:", 1)[1].strip()
            clip_uri = clip_node.attrib.get("text", "").split("CLIPBOARD_TEXT:", 1)[1].strip() if clip_node is not None else ""
            if adm_uri != expected_url:
                raise AssertionError(f"ADM did NOT receive redirected final URL! expected={expected_url!r}, got={adm_uri!r}")
            if clip_uri != expected_url:
                raise AssertionError(f"Clipboard did NOT receive redirected final URL! expected={expected_url!r}, got={clip_uri!r}")
            screencap(scr_dir, "27-adm-received-final-url.png")
            log(f"  Verified ADM + Clipboard exact redirected URL & subtitle selection: {adm_uri}")

            # Verify multi-link HLS .m3u8 manifest grabber (master + 1080p + 720p + audio + subtitle)
            hls_play_url = f"http://127.0.0.1:{port}/play-link/hls-redirect.php?id=55"
            adb("shell", "am", "force-stop", PKG)
            time.sleep(0.5)
            adb_am_start(
                "-W",
                "-n", f"{PKG}/com.delfan.madp.Acts.activity_video_player",
                "--es", "url", hls_play_url,
                "--es", "sub_title", "",
                "--es", "title", "HLS Multi Variant Test",
                "--es", "Type_movie", "1",
                "--es", "Link_Id", "55",
            )
            time.sleep(2.0)
            _wait_for_stream_grabbed(timeout=30.0)
            root_hls, _ = _open_stream_dialog()
            deadline_hls = time.time() + 6.0
            tab_all_hls = find_node(root_hls, resource_id="TabFilterAll_StreamDlg")
            tab_all_txt = tab_all_hls.attrib.get("text", "") if tab_all_hls is not None else ""
            while "(5)" not in tab_all_txt and time.time() < deadline_hls:
                time.sleep(0.7)
                root_hls = dump_ui()
                tab_all_hls = find_node(root_hls, resource_id="TabFilterAll_StreamDlg")
                tab_all_txt = tab_all_hls.attrib.get("text", "") if tab_all_hls is not None else ""
            if "(5)" not in tab_all_txt:
                raise AssertionError(f"Expected 5 HLS manifest links grabbed (master + 1080p + 720p + audio + sub), got tab={tab_all_txt!r}")
            screencap(scr_dir, "28-player-hls-multilinks.png")
            tap_node(find_node(root_hls, resource_id="TabFilterStream_StreamDlg"), "TabFilterStream_StreamDlg", wait=0.6)
            root_hls_stream = dump_ui()
            card_1080 = find_node(root_hls_stream, text_contains="1080p")
            if card_1080 is None:
                raise AssertionError("Missing 1080p HLS variant card in HLS/DASH filter tab")
            tap_node(card_1080, "select 1080p HLS variant", wait=0.6)
            root_hls_sel = dump_ui()
            sel_hls_url = find_node(root_hls_sel, resource_id="TxtUrl_StreamDlg").attrib.get("text", "").strip()
            if sel_hls_url != hls_1080_url:
                raise AssertionError(f"Expected selected 1080p HLS URL {hls_1080_url!r}, got {sel_hls_url!r}")
            tap_node(find_node(root_hls_sel, resource_id="BtnClose_StreamDlg"), "close HLS dialog", wait=0.8)
            log(f"  Verified HLS multi-variant manifest grabbing & selection: {hls_1080_url}")

            # Verify player controller Grabber ON/OFF toggle button stops capture when OFF & persists across restart
            root_tog, _ = _ensure_player_controls_visible(timeout=20.0)
            btn_tog = find_node(root_tog, resource_id="btn_grabber_toggle")
            tap_node(btn_tog, "btn_grabber_toggle -> OFF", wait=1.0)
            root_after_off, _ = _ensure_player_controls_visible(timeout=15.0)
            btn_tog_off = find_node(root_after_off, resource_id="btn_grabber_toggle")
            if "خاموش" not in btn_tog_off.attrib.get("content-desc", ""):
                raise AssertionError(f"Expected grabber toggle OFF desc, got {btn_tog_off.attrib}")
            screencap(scr_dir, "29-player-grabber-toggled-off.png")

            # Relaunch player on 2-hop URL while Grabber is OFF -> should NOT capture any link!
            twohop_play_url = f"http://127.0.0.1:{port}/play-link/twohop.php?id=99"
            adb("shell", "am", "force-stop", PKG)
            time.sleep(0.5)
            adb_am_start(
                "-W",
                "-n", f"{PKG}/com.delfan.madp.Acts.activity_video_player",
                "--es", "url", twohop_play_url,
                "--es", "sub_title", "",
                "--es", "title", "Grabber Off Persistence Test",
                "--es", "Type_movie", "1",
                "--es", "Link_Id", "99",
            )
            time.sleep(2.5)
            root_off_ctl, btn_dl_off = _ensure_player_controls_visible(timeout=20.0)
            if "آماده" in btn_dl_off.attrib.get("content-desc", ""):
                raise AssertionError(f"Grabber should NOT capture links while OFF! desc={btn_dl_off.attrib}")
            btn_tog_persisted = find_node(root_off_ctl, resource_id="btn_grabber_toggle")
            if "خاموش" not in btn_tog_persisted.attrib.get("content-desc", ""):
                raise AssertionError(f"Grabber OFF state did not persist across restart: {btn_tog_persisted.attrib}")

            root_dlg_off, tv_off = _open_stream_dialog()
            if find_node(root_dlg_off, resource_id="TxtEmptyLinks_StreamDlg") is None:
                raise AssertionError("Expected TxtEmptyLinks_StreamDlg visible while Grabber is OFF")
            if tv_off.attrib.get("text", "").strip() != "لینکی انتخاب نشده است":
                raise AssertionError(f"Expected empty URL placeholder when Grabber OFF, got {tv_off.attrib}")
            screencap(scr_dir, "30-player-dialog-grabber-off.png")

            # Turn Grabber back ON from inside the dialog & verify live active scan captures twohop_url
            tap_node(find_node(root_dlg_off, resource_id="BtnDialogGrabberToggle_StreamDlg"), "BtnDialogGrabberToggle_StreamDlg -> ON", wait=1.5)
            deadline_on = time.time() + 10.0
            shown_2h = ""
            while time.time() < deadline_on:
                root_dlg_on = dump_ui()
                n_2h = find_node(root_dlg_on, resource_id="TxtUrl_StreamDlg")
                shown_2h = n_2h.attrib.get("text", "").strip() if n_2h is not None else ""
                if shown_2h == twohop_url:
                    break
                time.sleep(0.7)
            if shown_2h != twohop_url:
                raise AssertionError(f"Re-enabling Grabber did not capture 2-hop URL {twohop_url!r}, got {shown_2h!r}")
            screencap(scr_dir, "31-player-dialog-grabber-resumed-on.png")
            log(f"  Verified Grabber OFF persistence + live ON re-capture of 2-hop URL: {twohop_url}")

            # Verify off-thread HTTP fallback resolver + ADM handoff when target returns 503 to ExoPlayer
            fb_play_url = f"http://127.0.0.1:{port}/play-link/adm-fallback.php?id=77"
            adb("shell", "am", "force-stop", PKG)
            time.sleep(0.5)
            adb_am_start(
                "-W",
                "-n", f"{PKG}/com.delfan.madp.Acts.activity_video_player",
                "--es", "url", fb_play_url,
                "--es", "sub_title", "",
                "--es", "title", "Fallback Resolver Test",
                "--es", "Type_movie", "1",
                "--es", "Link_Id", "77",
            )
            time.sleep(2.0)
            root_dlg_fb, tv_fb = _open_stream_dialog()
            deadline_fb = time.time() + 10.0
            shown_fb = tv_fb.attrib.get("text", "").strip()
            while shown_fb != fallback_url and time.time() < deadline_fb:
                time.sleep(0.5)
                root_dlg_fb = dump_ui()
                n_fb = find_node(root_dlg_fb, resource_id="TxtUrl_StreamDlg")
                shown_fb = n_fb.attrib.get("text", "").strip() if n_fb is not None else ""
            if shown_fb != fallback_url:
                raise AssertionError(f"Fallback resolver mismatch: expected={fallback_url!r}, got={shown_fb!r}")
            tap_node(find_node(root_dlg_fb, resource_id="BtnAdmUrl_StreamDlg"), "BtnAdmUrl_StreamDlg (fallback ADM)", wait=2.0)
            root_adm_fb, _ = wait_for_node(timeout=8.0, text_contains="ADM_RECEIVED_URI:")
            adm_fb_node = find_node(root_adm_fb, text_contains="ADM_RECEIVED_URI:")
            adm_fb_uri = adm_fb_node.attrib.get("text", "").split("ADM_RECEIVED_URI:", 1)[1].strip()
            if adm_fb_uri != fallback_url:
                raise AssertionError(f"ADM fallback mismatch: expected={fallback_url!r}, got={adm_fb_uri!r}")
            log(f"  Verified off-thread HTTP fallback resolver + ADM handoff: {adm_fb_uri}")
            log(f"  Local redirect server recorded {len(hits)} HTTP hits")
        finally:
            if dummy_adm_installed:
                adb("shell", "pm", "uninstall", "com.dv.adm", check=False)
            adb("reverse", "--remove", f"tcp:{port}", check=False)
            srv.shutdown()
            srv.server_close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--screenshots", default="screenshots")
    parser.add_argument("--logs", default="logs")
    args = parser.parse_args()

    scr_dir = Path(args.screenshots)
    log_dir = Path(args.logs)
    scr_dir.mkdir(parents=True, exist_ok=True)
    log_dir.mkdir(parents=True, exist_ok=True)

    log_lines = []
    report = []

    def log(msg):
        print(msg, flush=True)
        log_lines.append(msg)

    def step(msg):
        log(f"\n=== {msg} ===")
        report.append(f"[PASS-START] {msg}")

    try:
        adb("logcat", "-c", check=False)
        adb("shell", "svc", "wifi", "enable", check=False)
        adb("shell", "svc", "data", "enable", check=False)
        adb("shell", "pm", "uninstall", PKG, check=False)

        step("1. Install patched signed APK")
        install_out = adb("install", "-r", "-g", args.apk)
        (log_dir / "adb-install.log").write_text(install_out, encoding="utf-8")
        log(install_out.strip())

        step("2. Launch splash -> main & verify Offline Favorites empty state without login prompt")
        launch_and_dismiss_to_main()
        screencap(scr_dir, "01-main-home.png")

        root = tap_bottom_tab("علاقه مندی")
        assert_text_absent(root, "جهت مشاهده لیست علاقه مندی")
        assert_text_absent(root, "ورود به حساب کاربری")
        for tab_label in ("همه", "سینمایی", "سریال", "پوشه‌ها"):
            if find_node(root, text=tab_label, max_y=1200) is None:
                raise AssertionError(f"Missing tab {tab_label!r} in Favorites")
        if find_node(root, text_contains="هنوز فیلم یا سریالی") is None:
            raise AssertionError("Expected empty Favorites message on fresh install")
        screencap(scr_dir, "02-favs-empty.png")

        step("3. Open real Movie #1 -> verify clean string & offline favorite button visible")
        cinema_root, cinema_cards = wait_for_cinema_cards("سینمایی", min_count=3, timeout=65.0)
        cinema_card_width = cinema_cards[0]["width"]
        cinema_first_row = [c for c in cinema_cards if abs(c["y1"] - cinema_cards[0]["y1"]) <= 20]
        if len(cinema_first_row) != 3:
            raise AssertionError(f"Expected 3 columns in Cinema reference grid, found {len(cinema_first_row)} cards on first row: {cinema_first_row}")
        cinema_persian_titles = [c["title"] for c in cinema_cards[:9]]
        log(f"  Cinema reference grid: {len(cinema_first_row)} columns, card_width={cinema_card_width}px, sample={cinema_persian_titles[:3]}")

        movie1_title = cinema_cards[0]["title"]
        tap_node(cinema_cards[0]["node"], f"سینمایی card #0 ({movie1_title})", wait=2.0)
        log(f"  Opened Movie #1: {movie1_title}")
        root, btn_fav = wait_for_details_loaded(timeout=15.0)
        assert_text_absent(root, "Mahroid")
        txt_btn = find_node(root, resource_id="TxtBtnFave_ADV")
        if txt_btn is None or "افزودن به علاقه‌مندی" not in txt_btn.attrib.get("text", ""):
            raise AssertionError(f"Unexpected initial button text: {txt_btn.attrib if txt_btn is not None else None}")
        des_node = find_node(root, resource_id="TxtDes_ActDetialsVideo")
        movie1_des_snippet = ""
        if des_node is not None:
            raw_des = des_node.attrib.get("text", "").strip()
            for line in raw_des.splitlines():
                line = line.strip()
                if len(line) >= 12 and not line.startswith("نام اصلی"):
                    movie1_des_snippet = line[:18]
                    break
        screencap(scr_dir, "03-details-before-fav.png")

        step("4. Tap Favorite -> create folder 'Weekend' inside dialog -> save")
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, edit_folder = wait_for_node(timeout=6.0, resource_id="EditNewFolder_FavDlg")
        screencap(scr_dir, "04-fav-dialog-empty.png")
        enter_text_in_field(edit_folder, "Weekend")
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnCreateFolder_FavDlg"), "BtnCreateFolder_FavDlg")
        root = dump_ui()
        if find_node(root, text="Weekend") is None:
            raise AssertionError("Newly created folder 'Weekend' did not appear in dialog")
        screencap(scr_dir, "05-fav-dialog-folder-created.png")
        tap_node(find_node(root, resource_id="BtnSaveFav_FavDlg"), "BtnSaveFav_FavDlg", wait=1.0)
        root, txt_btn_after = wait_for_node(timeout=6.0, resource_id="TxtBtnFave_ADV")
        if "در علاقه‌مندی‌ها" not in txt_btn_after.attrib.get("text", ""):
            raise AssertionError(f"Expected saved state text, got: {txt_btn_after.attrib}")
        screencap(scr_dir, "06-details-after-fav.png")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)

        step("5. Save real Movie #2 into Weekend + Sci-Fi & real Series #1 without folder")
        movie2_title = open_media_card("سینمایی", 1)
        log(f"  Opened Movie #2: {movie2_title}")
        root, btn_fav = wait_for_details_loaded(timeout=15.0)
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, _ = wait_for_node(timeout=6.0, resource_id="BtnSaveFav_FavDlg")
        tap_node(find_node(root, text="Weekend"), "check Weekend")
        root = dump_ui()
        enter_text_in_field(find_node(root, resource_id="EditNewFolder_FavDlg"), "Sci-Fi")
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnCreateFolder_FavDlg"), "BtnCreateFolder_FavDlg")
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnSaveFav_FavDlg"), "BtnSaveFav_FavDlg", wait=1.0)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)

        series1_title = open_media_card("سریال", 0)
        log(f"  Opened Series #1: {series1_title}")
        root, btn_fav = wait_for_details_loaded(timeout=15.0)
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, btn_save = wait_for_node(timeout=6.0, resource_id="BtnSaveFav_FavDlg")
        tap_node(btn_save, "BtnSaveFav_FavDlg", wait=1.0)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)

        step("6. Verify Favorites Tabs, 3-Column Grid Layout & ASCII IMDb: All / Movies / Series / Folders / Folder Detail / Rename")
        root = tap_bottom_tab("علاقه مندی")
        for t in (movie1_title, movie2_title, series1_title):
            if find_node(root, text=t) is None:
                raise AssertionError(f"Missing {t!r} in All tab")
        fav_cards = measure_cards_layout(root)
        if len(fav_cards) != 3:
            raise AssertionError(f"Expected 3 favorite cards in All tab, got {len(fav_cards)}: {[c['title'] for c in fav_cards]}")
        fav_card_width = fav_cards[0]["width"]
        row_y_spread = max(c["y1"] for c in fav_cards) - min(c["y1"] for c in fav_cards)
        if abs(fav_card_width - cinema_card_width) > 12:
            raise AssertionError(
                f"Favorites card width ({fav_card_width}px) does not match Cinema 3-column width ({cinema_card_width}px)!"
            )
        if row_y_spread > 25:
            raise AssertionError(f"Expected all 3 favorites on one 3-column row, y1 spread was {row_y_spread}px")
        for fc in fav_cards:
            if fc["imdb"] and re.search(r"[\u06F0-\u06F9\u0660-\u0669]", fc["imdb"]):
                raise AssertionError(f"Expected ASCII IMDb digits on card {fc['title']!r}, got {fc['imdb']!r}")
        log(f"  Favorites 3-column grid verified: width={fav_card_width}px (Cinema={cinema_card_width}px), y_spread={row_y_spread}px, imdb={[c['imdb'] for c in fav_cards]}")
        screencap(scr_dir, "07-favs-all-tab.png")

        tap_node(find_node(root, resource_id="TabFaraB_Frg5"), "Movies sub-tab")
        root = dump_ui()
        if find_node(root, text=movie1_title) is None or find_node(root, text=movie2_title) is None:
            raise AssertionError("Movies sub-tab missing saved movies")
        if series1_title not in (movie1_title, movie2_title):
            assert_text_absent(root, series1_title)
        screencap(scr_dir, "08-favs-movies-tab.png")

        tap_node(find_node(root, resource_id="TabB_Frg5"), "Series sub-tab")
        root = dump_ui()
        if find_node(root, text=series1_title) is None:
            raise AssertionError("Series sub-tab missing saved series")
        screencap(scr_dir, "09-favs-series-tab.png")

        tap_node(find_node(root, resource_id="TabFolders_Frg5"), "Folders sub-tab")
        root = dump_ui()
        if find_node(root, text="Weekend") is None or find_node(root, text="Sci-Fi") is None:
            raise AssertionError("Folders tab missing Weekend / Sci-Fi")
        screencap(scr_dir, "10-favs-folders-tab.png")

        tap_node(find_node(root, text="Weekend"), "open Weekend folder")
        root, hdr = wait_for_node(timeout=6.0, resource_id="TxtFolderHeader_Frg5")
        if find_node(root, text=movie1_title) is None or find_node(root, text=movie2_title) is None:
            raise AssertionError("Folder detail missing items inside Weekend")
        screencap(scr_dir, "11-favs-folder-detail.png")

        # Rename Weekend -> TopPicks
        tap_node(find_node(root, resource_id="BtnRenameFolder_Frg5"), "BtnRenameFolder_Frg5")
        root = dump_ui()
        edit_box = None
        for node in root.iter("node"):
            if "EditText" in node.attrib.get("class", ""):
                edit_box = node
                break
        if edit_box is None:
            raise AssertionError("Rename dialog EditText not found")
        tap_node(edit_box, "rename EditText", wait=0.4)
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(12):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        adb("shell", "input", "text", "TopPicks")
        time.sleep(0.4)
        root = dump_ui()
        tap_node(find_node(root, text="ذخیره"), "save rename", wait=0.8)
        root, hdr2 = wait_for_node(timeout=5.0, resource_id="TxtFolderHeader_Frg5")
        if "TopPicks" not in hdr2.attrib.get("text", ""):
            raise AssertionError(f"Expected renamed header TopPicks, got: {hdr2.attrib}")
        screencap(scr_dir, "12-favs-folder-renamed.png")
        tap_node(find_node(root, resource_id="BtnBackFolder_Frg5"), "back to folder list")

        step("7. Test SAF Export -> Remove Series Item -> SAF Import Restore")
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnExport_Frg5"), "BtnExport_Frg5", wait=2.5)
        deadline = time.time() + 10.0
        save_doc_btn = None
        while time.time() < deadline:
            root = dump_ui()
            for node in root.iter("node"):
                cls = node.attrib.get("class", "")
                txt = node.attrib.get("text", "").strip().lower()
                rid = node.attrib.get("resource-id", "")
                if (txt in ("save", "ذخیره") or (rid == "android:id/button1" and "Button" in cls)):
                    if parse_bounds(node.attrib.get("bounds", "")):
                        save_doc_btn = node
                        break
            if save_doc_btn is not None:
                # Re-dump once after 0.6s so IME animation has settled
                time.sleep(0.6)
                root = dump_ui()
                for node in root.iter("node"):
                    cls = node.attrib.get("class", "")
                    txt = node.attrib.get("text", "").strip().lower()
                    rid = node.attrib.get("resource-id", "")
                    if (txt in ("save", "ذخیره") or (rid == "android:id/button1" and "Button" in cls)):
                        save_doc_btn = node
                        break
                break
            time.sleep(0.8)
        screencap(scr_dir, "13-saf-export-picker.png")
        if save_doc_btn is None:
            raise AssertionError("DocumentsUI SAVE button not found. Visible UI:\n" + "\n".join(list_all_texts(root)[:40]))
        tap_node(save_doc_btn, f"DocumentsUI SAVE ({save_doc_btn.attrib.get('text')!r} @ {save_doc_btn.attrib.get('bounds')})", wait=2.0)

        # Open Series sub-tab, tap Series #1 directly from Offline Favorites, remove it!
        root = dump_ui()
        tap_node(find_node(root, resource_id="TabB_Frg5"), "Series sub-tab")
        root = dump_ui()
        tap_node(find_node(root, text=series1_title), "open Series #1 from offline favs", wait=2.0)
        root, btn_fav = wait_for_node(timeout=8.0, resource_id="BtnAddFave_ADV")
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, btn_rem = wait_for_node(timeout=6.0, resource_id="BtnRemoveFav_FavDlg")
        tap_node(btn_rem, "BtnRemoveFav_FavDlg", wait=0.8)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)

        root = dump_ui()
        assert_text_absent(root, series1_title)

        tap_node(find_node(root, resource_id="BtnImport_Frg5"), "BtnImport_Frg5", wait=2.5)
        imp_deadline = time.time() + 10.0
        json_file_node = None
        tried_dl = False
        while time.time() < imp_deadline:
            root = dump_ui()
            json_file_node = find_node(root, text_contains="mymoviebox-favorites")
            if json_file_node is not None:
                break
            if not tried_dl:
                dl_chip = find_node(root, text="Downloads")
                if dl_chip is not None:
                    tried_dl = True
                    tap_node(dl_chip, "DocumentsUI Downloads chip", wait=1.5)
                    continue
            time.sleep(0.8)
        screencap(scr_dir, "14-saf-import-picker.png")
        if json_file_node is None:
            raise AssertionError("Exported JSON not visible in DocumentsUI import picker. Visible UI:\n" + "\n".join(list_all_texts(root)[:40]))
        tap_node(json_file_node, "pick exported json", wait=2.0)
        root, _ = wait_for_node(timeout=8.0, text=series1_title)
        log("  [PASS] SAF Export -> Delete -> SAF Import restored Series #1!")

        step("8. Test Persistence Across Full Process Kill (am force-stop) & True Offline Mode")
        adb("shell", "am", "force-stop", PKG)
        time.sleep(1.0)
        launch_and_dismiss_to_main()
        root = tap_bottom_tab("علاقه مندی")
        for t in (movie1_title, movie2_title, series1_title):
            if find_node(root, text=t) is None:
                raise AssertionError(f"Persistence failed after force-stop: missing {t!r}")
        tap_node(find_node(root, resource_id="TabFolders_Frg5"), "Folders tab after restart")
        root = dump_ui()
        if find_node(root, text="TopPicks") is None or find_node(root, text="Sci-Fi") is None:
            raise AssertionError("Folders failed to persist after force-stop")
        screencap(scr_dir, "15-favs-after-restart.png")

        # Disable network completely & verify opening saved Favorite shows prefilled Details & folder count!
        adb("shell", "svc", "wifi", "disable", check=False)
        adb("shell", "svc", "data", "disable", check=False)
        time.sleep(1.0)
        tap_node(find_node(root, resource_id="TabAll_Frg5"), "All tab offline")
        root = dump_ui()
        tap_node(find_node(root, text=movie1_title), f"open {movie1_title} completely offline", wait=2.0)
        root, btn_fav_off = wait_for_node(timeout=8.0, resource_id="BtnAddFave_ADV")
        txt_off = find_node(root, resource_id="TxtBtnFave_ADV")
        if txt_off is None or "در علاقه‌مندی‌ها" not in txt_off.attrib.get("text", ""):
            raise AssertionError(f"Offline Details missing saved state: {txt_off.attrib if txt_off is not None else None}")
        if movie1_des_snippet and find_node(root, text_contains=movie1_des_snippet) is None:
            raise AssertionError(f"Offline Details failed to prefill saved description ({movie1_des_snippet!r}) without network!")
        screencap(scr_dir, "16-offline-details-no-network.png")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)

        step("9. Guest English/Persian Movie Title Toggle & Cold-Restart Persistence")
        adb("shell", "svc", "wifi", "enable", check=False)
        adb("shell", "svc", "data", "enable", check=False)
        time.sleep(2.5)
        wait_for_cinema_cards("سینمایی", min_count=3, timeout=65.0)
        root, toggle_btn, toggle_label = open_drawer_from_cinema()
        if "انگلیسی" not in toggle_label:
            raise AssertionError(f"Expected drawer label for English switch, got: {toggle_label!r}")
        screencap(scr_dir, "17-drawer-title-lang-en-option.png")

        tap_node(toggle_btn, "DrMenu_RelChangeMovieTitle -> EN", wait=1.5)
        time.sleep(1.0)
        cur_ui = dump_ui()
        assert_text_absent(cur_ui, "وارد حساب کاربری")
        assert_text_absent(cur_ui, "جهت تغییر زبان عناوین")
        wait_for_main_after_reload(timeout=45.0)

        root_en, en_cards = wait_for_cinema_cards("سینمایی", min_count=3, timeout=65.0)
        en_titles = [c["title"] for c in en_cards[:9]]
        latin_count = sum(1 for t in en_titles if re.search(r"[A-Za-z]{2,}", t))
        log(f"  English mode Cinema titles ({latin_count}/{len(en_titles)} Latin): {en_titles[:6]}")
        if latin_count < 2 or en_titles[:6] == cinema_persian_titles[:6]:
            raise AssertionError(
                f"Expected English titles in Cinema after toggling without login! Before={cinema_persian_titles[:6]}, After={en_titles[:6]}"
            )
        screencap(scr_dir, "18-cinema-english-titles.png")

        _, toggle_btn_fa, toggle_label_fa = open_drawer_from_cinema()
        if "فارسی" not in toggle_label_fa:
            raise AssertionError(f"Expected drawer label to switch to Persian ('فارسی'), got: {toggle_label_fa!r}")
        screencap(scr_dir, "19-drawer-title-lang-fa-option.png")

        # Kill app completely and verify English titles persist across cold restart without login
        adb("shell", "am", "force-stop", PKG)
        time.sleep(1.0)
        launch_and_dismiss_to_main()
        _, en_cards_after_restart = wait_for_cinema_cards("سینمایی", min_count=3, timeout=65.0)
        en_titles_restart = [c["title"] for c in en_cards_after_restart[:9]]
        latin_restart = sum(1 for t in en_titles_restart if re.search(r"[A-Za-z]{2,}", t))
        log(f"  After cold restart Cinema titles ({latin_restart}/{len(en_titles_restart)} Latin): {en_titles_restart[:6]}")
        if latin_restart < 2:
            raise AssertionError(f"English titles did not persist across am force-stop! Got: {en_titles_restart[:6]}")
        screencap(scr_dir, "20-cinema-english-after-restart.png")

        # Toggle back to Persian titles cleanly
        _, toggle_btn_back, _ = open_drawer_from_cinema()
        tap_node(toggle_btn_back, "DrMenu_RelChangeMovieTitle -> FA", wait=1.5)
        wait_for_main_after_reload(timeout=45.0)
        _, fa_cards_restored = wait_for_cinema_cards("سینمایی", min_count=3, timeout=65.0)
        fa_titles_restored = [c["title"] for c in fa_cards_restored[:9]]
        persian_count = sum(1 for t in fa_titles_restored if re.search(r"[\u0600-\u06FF]{2,}", t))
        log(f"  Restored Persian Cinema titles ({persian_count}/{len(fa_titles_restored)} Persian): {fa_titles_restored[:6]}")
        if persian_count < 2:
            raise AssertionError(f"Expected Persian titles restored after toggling back! Got: {fa_titles_restored[:6]}")
        _, _, restored_label = open_drawer_from_cinema()
        if "انگلیسی" not in restored_label:
            raise AssertionError(f"Expected drawer label back to English option, got: {restored_label!r}")
        screencap(scr_dir, "21-cinema-persian-restored.png")
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)

        step("10. Probe real Movie #1 play button in app & prepare local 302 redirect test server")
        cur_cards = measure_cards_layout(dump_ui())
        if cur_cards:
            tap_node(cur_cards[0]["node"], "open Movie #1 for play check", wait=2.0)
            root_det, _ = wait_for_details_loaded(timeout=15.0)
            play_btn = find_node(root_det, resource_id="BtnPlay_ActDetialsVideo")
            if play_btn is not None:
                tap_node(play_btn, "BtnPlay_ActDetialsVideo", wait=2.5)
                screencap(scr_dir, "22-real-movie-play-tap.png")
                adb("shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(0.8)
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.5)

        step("11. Test Video Player Stream Grabber (302 Redirect, Icon State, Dialog Actions, Landscape, ADM Handoff)")
        run_player_and_adm_e2e(scr_dir, log_dir, log)

        step("12. Inspect logcat for zero fatal exceptions")
        lc = adb("logcat", "-d", "-t", "5000", check=False)
        fatal_hits = []
        for line in lc.splitlines():
            if PKG in line or "AndroidRuntime" in line or "favs" in line:
                if any(err in line for err in ("FATAL EXCEPTION", "IllegalAccessError", "NoSuchMethodError", "NoSuchFieldError", "VerifyError", "Resources$NotFoundException")):
                    fatal_hits.append(line)
        if fatal_hits:
            raise AssertionError("Fatal errors detected in logcat:\n" + "\n".join(fatal_hits[:20]))

        (log_dir / "e2e-summary.txt").write_text("\n".join(report) + "\nALL CHECKS PASSED\n", encoding="utf-8")
        log("\nALL E2E EMULATOR CHECKS PASSED!")
    except Exception as exc:
        log(f"\n[FAIL] {exc}")
        log(traceback.format_exc())
        report.append(f"[FAIL] {exc}")
        (log_dir / "e2e-summary.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
        try:
            screencap(scr_dir, "99-failure-screen.png")
        except Exception:
            pass
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/uidump_fail.xml", check=False)
            (log_dir / "uidump_fail.xml").write_text(adb("shell", "cat", "/sdcard/uidump_fail.xml", check=False), encoding="utf-8")
        except Exception:
            pass
        raise
    finally:
        adb("shell", "svc", "wifi", "enable", check=False)
        adb("shell", "svc", "data", "enable", check=False)
        try:
            (log_dir / "e2e-stdout.log").write_text("\n".join(log_lines) + "\n", encoding="utf-8")
        except Exception:
            pass
        try:
            focus = adb("shell", "dumpsys", "window", "windows", check=False)
            focus_lines = [l for l in focus.splitlines() if "mCurrentFocus" in l or "mFocusedApp" in l]
            (log_dir / "window-focus.txt").write_text("\n".join(focus_lines) + "\n", encoding="utf-8")
        except Exception:
            pass
        try:
            lc = adb("logcat", "-d", "-t", "5000", check=False)
            (log_dir / "logcat-e2e.txt").write_text(lc, encoding="utf-8")
        except Exception:
            pass
        try:
            root_res = subprocess.run(["adb", "root"], text=True, capture_output=True)
            if root_res.returncode == 0:
                time.sleep(1.0)
                prefs_xml = adb("shell", "cat", f"/data/data/{PKG}/shared_prefs/offline_favorites_v1.xml", check=False)
                (log_dir / "offline_favorites_v1.xml").write_text(prefs_xml, encoding="utf-8")
                my_prefs_xml = adb("shell", "cat", f"/data/data/{PKG}/shared_prefs/MyPrefers.xml", check=False)
                (log_dir / "MyPrefers.xml").write_text(my_prefs_xml, encoding="utf-8")
                grab_prefs_xml = adb("shell", "cat", f"/data/data/{PKG}/shared_prefs/offline_stream_grabber.xml", check=False)
                (log_dir / "offline_stream_grabber.xml").write_text(grab_prefs_xml, encoding="utf-8")
        except Exception:
            pass


if __name__ == "__main__":
    main()
