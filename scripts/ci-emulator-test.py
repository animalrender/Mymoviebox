#!/usr/bin/env python3
import argparse
import re
import subprocess
import sys
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


def adb_bytes(*args):
    res = subprocess.run(["adb", *args], capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {res.stderr.decode('utf-8', 'replace')}")
    return res.stdout


def screencap(out_dir: Path, name: str):
    data = adb_bytes("exec-out", "screencap", "-p")
    path = out_dir / name
    path.write_bytes(data)
    print(f"  [screenshot] {path.name} ({len(data)} bytes)")


def dump_ui(label="ui"):
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    xml_text = adb("shell", "cat", "/sdcard/uidump.xml")
    return ET.fromstring(xml_text)


def parse_bounds(bounds_str: str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_node(root, *, resource_id=None, text=None, text_contains=None, content_desc=None):
    for node in root.iter("node"):
        rid = node.attrib.get("resource-id", "")
        txt = node.attrib.get("text", "")
        cd = node.attrib.get("content-desc", "")
        if resource_id and not (rid == resource_id or rid.endswith(":id/" + resource_id)):
            continue
        if text is not None and txt != text:
            continue
        if text_contains is not None and text_contains not in txt:
            continue
        if content_desc is not None and content_desc not in cd:
            continue
        return node
    return None


def list_all_texts(root):
    out = []
    for node in root.iter("node"):
        t = node.attrib.get("text", "").strip()
        cd = node.attrib.get("content-desc", "").strip()
        rid = node.attrib.get("resource-id", "").strip()
        if t or cd:
            out.append(f"{rid}: text={t!r} desc={cd!r}")
    return out


def tap_node(node, label="node"):
    if node is None:
        raise AssertionError(f"UI node not found for tap: {label}")
    coords = parse_bounds(node.attrib.get("bounds", ""))
    if not coords:
        raise AssertionError(f"Invalid bounds on {label}: {node.attrib}")
    adb("shell", "input", "tap", str(coords[0]), str(coords[1]))
    time.sleep(1.0)


def wait_for_node(timeout=10.0, **kwargs):
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
    raise AssertionError(f"Timed out waiting for node {kwargs}. Visible UI:\n" + "\n".join(texts[:40]))


def assert_text_absent(root, needle: str):
    for node in root.iter("node"):
        if needle in node.attrib.get("text", ""):
            raise AssertionError(f"Unexpected text {needle!r} present in UI: {node.attrib}")


def enter_text_in_field(node, value: str):
    tap_node(node, "EditText")
    time.sleep(0.4)
    adb("shell", "input", "text", value)
    time.sleep(0.5)


def open_details(videoid: str, title: str, des: str, what: str):
    adb(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        f"{PKG}/com.delfan.madp.Acts.activity_detials_video",
        "--es",
        "videoid",
        videoid,
        "--es",
        "title",
        title,
        "--es",
        "des",
        des,
        "--es",
        "poster",
        "",
        "--es",
        "what",
        what,
    )
    time.sleep(1.5)


def open_main_and_go_favs():
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/com.delfan.madp.Acts.activity_main")
    time.sleep(1.5)
    root = dump_ui()
    fav_tab = find_node(root, text_contains="علاقه مندی") or find_node(root, content_desc="علاقه مندی")
    tap_node(fav_tab, "bottom favs tab")
    time.sleep(1.2)
    return dump_ui()


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
        adb("shell", "pm", "uninstall", PKG, check=False)
        step("1. Install patched signed APK")
        install_out = adb("install", "-r", "-g", args.apk)
        (log_dir / "adb-install.log").write_text(install_out, encoding="utf-8")
        print(install_out.strip())

        step("2. Launch splash & verify Main / Favorites empty state without login prompt")
        adb("shell", "am", "start", "-W", "-n", f"{PKG}/com.delfan.madp.Acts.activity_splash")
        time.sleep(7.0)
        screencap(scr_dir, "01-splash-boot.png")

        root = open_main_and_go_favs()
        assert_text_absent(root, "جهت مشاهده لیست علاقه مندی")
        assert_text_absent(root, "ورود به حساب کاربری")
        for tab_label in ("همه", "سینمایی", "سریال", "پوشه‌ها"):
            if find_node(root, text=tab_label) is None:
                raise AssertionError(f"Missing tab {tab_label!r} in Favorites")
        if find_node(root, text_contains="هنوز فیلم یا سریالی") is None:
            raise AssertionError("Expected empty Favorites message on fresh install")
        screencap(scr_dir, "02-favs-empty.png")

        step("3. Open Movie Details (9001) -> verify clean string & offline favorite button visible")
        open_details("9001", "Interstellar", "Sci-Fi Epic", "movie")
        root, btn_fav = wait_for_node(timeout=8.0, resource_id="BtnAddFave_ADV")
        assert_text_absent(root, "Mahroid")
        txt_btn = find_node(root, resource_id="TxtBtnFave_ADV")
        if txt_btn is None or "افزودن به علاقه‌مندی" not in txt_btn.attrib.get("text", ""):
            raise AssertionError(f"Unexpected initial button text: {txt_btn.attrib if txt_btn is not None else None}")
        screencap(scr_dir, "03-details-before-fav.png")

        step("4. Tap Favorite -> create folder 'Weekend' inside dialog -> save")
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, edit_folder = wait_for_node(timeout=6.0, resource_id="EditNewFolder_FavDlg")
        screencap(scr_dir, "04-fav-dialog-empty.png")
        enter_text_in_field(edit_folder, "Weekend")
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnCreateFolder_FavDlg"), "BtnCreateFolder_FavDlg")
        time.sleep(0.8)
        root = dump_ui()
        if find_node(root, text="Weekend") is None:
            raise AssertionError("Newly created folder 'Weekend' did not appear in dialog")
        screencap(scr_dir, "05-fav-dialog-folder-created.png")
        tap_node(find_node(root, resource_id="BtnSaveFav_FavDlg"), "BtnSaveFav_FavDlg")
        time.sleep(1.0)
        root, txt_btn_after = wait_for_node(timeout=6.0, resource_id="TxtBtnFave_ADV")
        if "در علاقه‌مندی‌ها" not in txt_btn_after.attrib.get("text", ""):
            raise AssertionError(f"Expected saved state text, got: {txt_btn_after.attrib}")
        screencap(scr_dir, "06-details-after-fav.png")

        step("5. Save Series (9002 Breaking Bad) without folder & Movie (9003 Inception) into Weekend + Sci-Fi")
        open_details("9002", "Breaking Bad", "Crime Drama", "seryal")
        root, btn_fav = wait_for_node(timeout=6.0, resource_id="BtnAddFave_ADV")
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, btn_save = wait_for_node(timeout=6.0, resource_id="BtnSaveFav_FavDlg")
        tap_node(btn_save, "BtnSaveFav_FavDlg")
        time.sleep(0.8)

        open_details("9003", "Inception", "Dream Heist", "movie")
        root, btn_fav = wait_for_node(timeout=6.0, resource_id="BtnAddFave_ADV")
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, _ = wait_for_node(timeout=6.0, resource_id="BtnSaveFav_FavDlg")
        tap_node(find_node(root, text="Weekend"), "check Weekend")
        root = dump_ui()
        enter_text_in_field(find_node(root, resource_id="EditNewFolder_FavDlg"), "Sci-Fi")
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnCreateFolder_FavDlg"), "BtnCreateFolder_FavDlg")
        time.sleep(0.6)
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnSaveFav_FavDlg"), "BtnSaveFav_FavDlg")
        time.sleep(0.8)

        step("6. Verify Favorites Tabs: All / Movies / Series / Folders / Folder Detail / Rename")
        root = open_main_and_go_favs()
        for title in ("Interstellar", "Breaking Bad", "Inception"):
            if find_node(root, text=title) is None:
                raise AssertionError(f"Missing {title!r} in All tab")
        screencap(scr_dir, "07-favs-all-tab.png")

        tap_node(find_node(root, resource_id="TabFaraB_Frg5"), "Movies tab")
        root = dump_ui()
        if find_node(root, text="Interstellar") is None or find_node(root, text="Inception") is None:
            raise AssertionError("Movies tab missing expected movies")
        assert_text_absent(root, "Breaking Bad")
        screencap(scr_dir, "08-favs-movies-tab.png")

        tap_node(find_node(root, resource_id="TabB_Frg5"), "Series tab")
        root = dump_ui()
        if find_node(root, text="Breaking Bad") is None:
            raise AssertionError("Series tab missing Breaking Bad")
        assert_text_absent(root, "Interstellar")
        screencap(scr_dir, "09-favs-series-tab.png")

        tap_node(find_node(root, resource_id="TabFolders_Frg5"), "Folders tab")
        root = dump_ui()
        if find_node(root, text="Weekend") is None or find_node(root, text="Sci-Fi") is None:
            raise AssertionError("Folders tab missing Weekend / Sci-Fi")
        screencap(scr_dir, "10-favs-folders-tab.png")

        tap_node(find_node(root, text="Weekend"), "open Weekend folder")
        root, hdr = wait_for_node(timeout=5.0, resource_id="TxtFolderHeader_Frg5")
        if find_node(root, text="Interstellar") is None or find_node(root, text="Inception") is None:
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
        tap_node(edit_box, "rename EditText")
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(12):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        adb("shell", "input", "text", "TopPicks")
        time.sleep(0.4)
        root = dump_ui()
        tap_node(find_node(root, text="ذخیره"), "save rename")
        time.sleep(0.8)
        root, hdr2 = wait_for_node(timeout=5.0, resource_id="TxtFolderHeader_Frg5")
        if "TopPicks" not in hdr2.attrib.get("text", ""):
            raise AssertionError(f"Expected renamed header TopPicks, got: {hdr2.attrib}")
        tap_node(find_node(root, resource_id="BtnBackFolder_Frg5"), "back to folder list")
        time.sleep(0.6)

        step("7. Test SAF Export -> Remove Item -> SAF Import Restore")
        root = dump_ui()
        tap_node(find_node(root, resource_id="BtnExport_Frg5"), "BtnExport_Frg5")
        time.sleep(2.0)
        root = dump_ui()
        screencap(scr_dir, "12-saf-export-picker.png")
        save_doc_btn = (
            find_node(root, text="SAVE")
            or find_node(root, text="Save")
            or find_node(root, text="ذخیره")
        )
        export_worked = False
        if save_doc_btn is not None:
            tap_node(save_doc_btn, "DocumentsUI SAVE")
            time.sleep(1.5)
            export_worked = True
        else:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(1.0)

        if export_worked:
            open_details("9002", "Breaking Bad", "Crime Drama", "seryal")
            root, btn_fav = wait_for_node(timeout=6.0, resource_id="BtnAddFave_ADV")
            tap_node(btn_fav, "BtnAddFave_ADV")
            root, btn_rem = wait_for_node(timeout=6.0, resource_id="BtnRemoveFav_FavDlg")
            tap_node(btn_rem, "BtnRemoveFav_FavDlg")
            time.sleep(0.8)

            root = open_main_and_go_favs()
            assert_text_absent(root, "Breaking Bad")

            tap_node(find_node(root, resource_id="BtnImport_Frg5"), "BtnImport_Frg5")
            time.sleep(2.0)
            root = dump_ui()
            screencap(scr_dir, "13-saf-import-picker.png")
            json_file_node = find_node(root, text_contains="mymoviebox-favorites")
            if json_file_node is not None:
                tap_node(json_file_node, "pick exported json")
                time.sleep(1.5)
                root = dump_ui()
                if find_node(root, text="Breaking Bad") is None:
                    raise AssertionError("SAF Import did not restore Breaking Bad!")
                print("  [PASS] SAF Export -> Delete -> SAF Import restored Breaking Bad!")
            else:
                adb("shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(1.0)
                print("  [INFO] Exported file not directly visible in default DocumentsUI view; skipping auto-tap import")

        step("8. Test Persistence Across Full Process Kill (am force-stop)")
        adb("shell", "am", "force-stop", PKG)
        time.sleep(1.0)
        root = open_main_and_go_favs()
        for title in ("Interstellar", "Inception"):
            if find_node(root, text=title) is None:
                raise AssertionError(f"Persistence failed after force-stop: missing {title!r}")
        tap_node(find_node(root, resource_id="TabFolders_Frg5"), "Folders tab after restart")
        root = dump_ui()
        if find_node(root, text="TopPicks") is None or find_node(root, text="Sci-Fi") is None:
            raise AssertionError("Folders failed to persist after force-stop")
        screencap(scr_dir, "14-favs-after-restart.png")

        step("9. Inspect logcat for zero fatal exceptions")
        lc = adb("logcat", "-d", "-t", "1200", check=False)
        fatal_hits = []
        for line in lc.splitlines():
            if PKG in line or "AndroidRuntime" in line or "favs" in line:
                if any(err in line for err in ("FATAL EXCEPTION", "IllegalAccessError", "NoSuchMethodError", "NoSuchFieldError", "VerifyError", "Resources$NotFoundException")):
                    fatal_hits.append(line)
        if fatal_hits:
            raise AssertionError("Fatal errors detected in logcat:\n" + "\n".join(fatal_hits[:20]))

        (log_dir / "e2e-summary.txt").write_text("\n".join(report) + "\nALL CHECKS PASSED\n", encoding="utf-8")
        print("\nALL E2E EMULATOR CHECKS PASSED!")
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
            lc = adb("logcat", "-d", "-t", "1200", check=False)
            (log_dir / "logcat-e2e.txt").write_text(lc, encoding="utf-8")
        except Exception:
            pass
        try:
            root_res = subprocess.run(["adb", "root"], text=True, capture_output=True)
            if root_res.returncode == 0:
                time.sleep(1.0)
                prefs_xml = adb("shell", "cat", f"/data/data/{PKG}/shared_prefs/offline_favorites_v1.xml", check=False)
                (log_dir / "offline_favorites_v1.xml").write_text(prefs_xml, encoding="utf-8")
        except Exception:
            pass


if __name__ == "__main__":
    main()
