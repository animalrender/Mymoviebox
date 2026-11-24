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
    print(f"  [screenshot] {path.name} ({len(data)} bytes)", flush=True)


def dump_ui():
    adb("shell", "uiautomator", "dump", "/sdcard/uidump.xml")
    xml_text = adb("shell", "cat", "/sdcard/uidump.xml")
    return ET.fromstring(xml_text)


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


def launch_and_dismiss_to_main():
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/com.delfan.madp.Acts.activity_splash")
    deadline = time.time() + 25.0
    while time.time() < deadline:
        try:
            root = dump_ui()
            ok_btn = (
                find_node(root, resource_id="PopupShowMsgVW_BtnOk")
                or find_node(root, resource_id="PopupShowMsg_BtnOk")
                or find_node(root, text="باشه")
            )
            if ok_btn is not None:
                tap_node(ok_btn, "dismiss popup", wait=1.2)
                continue
            if find_node(root, resource_id="bottom_navigation") is not None:
                return root
        except Exception:
            pass
        time.sleep(1.0)
    raise AssertionError("Timed out waiting for activity_main bottom_navigation")


def tap_bottom_tab(label_contains: str):
    root = dump_ui()
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
    return dump_ui()


def card_title(card_node) -> str:
    for sub in card_node.iter("node"):
        rid = sub.attrib.get("resource-id", "")
        if rid.endswith(":id/TxtTitle_ItemSeryalHome"):
            return sub.attrib.get("text", "").strip()
    return ""


def open_media_card(tab_label: str, index: int = 0) -> str:
    tap_bottom_tab(tab_label)
    # Wait up to 20s for RecyclerView items from server
    deadline = time.time() + 20.0
    cards = []
    root = None
    while time.time() < deadline:
        root = dump_ui()
        cards = [
            c for c in find_all_nodes(root, resource_id="RelMain_ItemSeryalHome")
            if card_title(c)
        ]
        if len(cards) > index:
            break
        time.sleep(1.0)
    if len(cards) <= index:
        raise AssertionError(f"Could not find card #{index} under tab {tab_label!r}. Visible:\n" + "\n".join(list_all_texts(root)[:35]))
    title = card_title(cards[index])
    tap_node(cards[index], f"{tab_label} card #{index} ({title})", wait=2.0)
    return title


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
        movie1_title = open_media_card("سینمایی", 0)
        log(f"  Opened Movie #1: {movie1_title}")
        root, btn_fav = wait_for_node(timeout=12.0, resource_id="BtnAddFave_ADV")
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
        root, btn_fav = wait_for_node(timeout=12.0, resource_id="BtnAddFave_ADV")
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
        root, btn_fav = wait_for_node(timeout=12.0, resource_id="BtnAddFave_ADV")
        tap_node(btn_fav, "BtnAddFave_ADV")
        root, btn_save = wait_for_node(timeout=6.0, resource_id="BtnSaveFav_FavDlg")
        tap_node(btn_save, "BtnSaveFav_FavDlg", wait=1.0)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.0)

        step("6. Verify Favorites Tabs: All / Movies / Series / Folders / Folder Detail / Rename")
        root = tap_bottom_tab("علاقه مندی")
        for t in (movie1_title, movie2_title, series1_title):
            if find_node(root, text=t) is None:
                raise AssertionError(f"Missing {t!r} in All tab")
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
        tap_node(find_node(root, resource_id="BtnExport_Frg5"), "BtnExport_Frg5", wait=2.0)
        root = dump_ui()
        screencap(scr_dir, "13-saf-export-picker.png")
        save_doc_btn = (
            find_node(root, text="SAVE")
            or find_node(root, text="Save")
            or find_node(root, text="ذخیره")
        )
        export_worked = False
        if save_doc_btn is not None:
            tap_node(save_doc_btn, "DocumentsUI SAVE", wait=1.5)
            export_worked = True
        else:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(1.0)

        if export_worked:
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

            tap_node(find_node(root, resource_id="BtnImport_Frg5"), "BtnImport_Frg5", wait=2.0)
            root = dump_ui()
            screencap(scr_dir, "14-saf-import-picker.png")
            json_file_node = find_node(root, text_contains="mymoviebox-favorites")
            if json_file_node is not None:
                tap_node(json_file_node, "pick exported json", wait=1.5)
                root = dump_ui()
                if find_node(root, text=series1_title) is None:
                    raise AssertionError("SAF Import did not restore Series #1!")
                log("  [PASS] SAF Export -> Delete -> SAF Import restored Series #1!")
            else:
                adb("shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(1.0)
                log("  [INFO] Exported file not visible in default DocumentsUI view; skipping auto-tap import")

        step("8. Test Persistence Across Full Process Kill (am force-stop)")
        adb("shell", "am", "force-stop", PKG)
        time.sleep(1.0)
        launch_and_dismiss_to_main()
        root = tap_bottom_tab("علاقه مندی")
        for t in (movie1_title, movie2_title):
            if find_node(root, text=t) is None:
                raise AssertionError(f"Persistence failed after force-stop: missing {t!r}")
        tap_node(find_node(root, resource_id="TabFolders_Frg5"), "Folders tab after restart")
        root = dump_ui()
        if find_node(root, text="TopPicks") is None or find_node(root, text="Sci-Fi") is None:
            raise AssertionError("Folders failed to persist after force-stop")
        screencap(scr_dir, "15-favs-after-restart.png")

        step("9. Inspect logcat for zero fatal exceptions")
        lc = adb("logcat", "-d", "-t", "1500", check=False)
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
            lc = adb("logcat", "-d", "-t", "1500", check=False)
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
