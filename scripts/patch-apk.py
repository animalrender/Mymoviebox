#!/usr/bin/env python3
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


def run(cmd, cwd=None):
    print(f"+ {' '.join(str(c) for c in cmd)}", flush=True)
    subprocess.run(cmd, cwd=cwd, check=True)


def find_sdk_tools():
    sdk_roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        "/usr/local/lib/android/sdk",
        str(Path.home() / "Android/Sdk"),
    ]
    sdk = next((Path(p) for p in sdk_roots if p and Path(p).is_dir()), None)
    if sdk is None:
        raise RuntimeError("Android SDK not found (set ANDROID_HOME or ANDROID_SDK_ROOT)")

    platforms = sorted((sdk / "platforms").glob("android-*"), reverse=True)
    if not platforms:
        raise RuntimeError(f"No Android platform jars found under {sdk / 'platforms'}")
    android_jar = None
    for p in platforms:
        cand = p / "android.jar"
        if cand.is_file():
            android_jar = cand
            break
    if android_jar is None:
        raise RuntimeError("android.jar not found")

    build_tools = sorted((sdk / "build-tools").glob("*"), reverse=True)
    d8 = zipalign = apksigner = None
    for bt in build_tools:
        if not bt.is_dir():
            continue
        c_d8 = bt / "d8"
        c_za = bt / "zipalign"
        c_as = bt / "apksigner"
        if c_d8.is_file() and c_za.is_file() and c_as.is_file():
            d8, zipalign, apksigner = c_d8, c_za, c_as
            break
    if not all([d8, zipalign, apksigner]):
        raise RuntimeError(f"d8/zipalign/apksigner not found under {sdk / 'build-tools'}")

    return android_jar, d8, zipalign, apksigner


def find_apktool(explicit_path):
    if explicit_path:
        p = Path(explicit_path)
        if not p.is_file():
            raise RuntimeError(f"Specified apktool not found: {p}")
        return p
    repo_root = Path(__file__).resolve().parent.parent
    candidates = [
        repo_root / "tools/apktool-bin/apktool.jar",
        *sorted((repo_root / "tools/apktool-bin").glob("apktool*.jar")),
        repo_root / "bin/apktool",
    ]
    for c in candidates:
        if c.is_file():
            return c
    which = shutil.which("apktool")
    if which:
        return Path(which)
    raise RuntimeError("apktool not found (run scripts/install-tools.sh or pass --apktool)")


def find_smali(decoded_dir: Path, rel_path: str) -> Path:
    matches = sorted(decoded_dir.glob(f"smali*/{rel_path}"))
    if not matches:
        raise RuntimeError(f"Could not find {rel_path} under {decoded_dir}/smali*")
    if len(matches) > 1:
        raise RuntimeError(f"Ambiguous matches for {rel_path}: {matches}")
    return matches[0]


def compile_patch_dex(repo_root: Path, work_dir: Path, android_jar: Path, d8: Path) -> Path:
    classes_dir = work_dir / "javac-classes"
    dex_out_dir = work_dir / "dex-patch"
    if classes_dir.exists():
        shutil.rmtree(classes_dir)
    if dex_out_dir.exists():
        shutil.rmtree(dex_out_dir)
    classes_dir.mkdir(parents=True)
    dex_out_dir.mkdir(parents=True)

    stubs_root = repo_root / "patches/stubs"
    src_root = repo_root / "patches/src"
    java_files = sorted(
        list(stubs_root.rglob("*.java")) + list(src_root.rglob("*.java"))
    )
    if not java_files:
        raise RuntimeError("No Java files found under patches/src")

    javac = shutil.which("javac")
    if not javac:
        raise RuntimeError("javac not found in PATH")

    run(
        [
            javac,
            "-source",
            "1.8",
            "-target",
            "1.8",
            "-encoding",
            "UTF-8",
            "-bootclasspath",
            str(android_jar),
            "-classpath",
            str(android_jar),
            "-d",
            str(classes_dir),
            *[str(f) for f in java_files],
        ]
    )

    # Package ONLY patches/src classes (com/delfan/madp/favs/**), NEVER compile-only stubs!
    favs_classes = sorted((classes_dir / "com/delfan/madp/favs").rglob("*.class"))
    if not favs_classes:
        raise RuntimeError("Expected compiled classes under com/delfan/madp/favs/")

    run(
        [
            str(d8),
            "--min-api",
            "21",
            "--lib",
            str(android_jar),
            "--output",
            str(dex_out_dir),
            *[str(c) for c in favs_classes],
        ]
    )

    dex_file = dex_out_dir / "classes.dex"
    if not dex_file.is_file():
        raise RuntimeError(f"d8 did not produce {dex_file}")
    return dex_file


def overlay_resources(repo_root: Path, decoded_dir: Path):
    patch_res = repo_root / "patches/res"
    target_res = decoded_dir / "res"
    for src in sorted(patch_res.rglob("*")):
        if src.is_dir():
            continue
        rel = src.relative_to(patch_res)
        dst = target_res / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        print(f"  copied res/{rel}")

    # Replace cracked watermark string Txt356 across values*/strings.xml
    replaced = 0
    for xml_path in sorted(target_res.glob("values*/strings.xml")):
        text = xml_path.read_text(encoding="utf-8")
        new_text, n = re.subn(
            r'(<string\s+name="Txt356"[^>]*>)[^<]*(</string>)',
            r"\1افزودن به علاقه‌مندی\2",
            text,
        )
        if n > 0:
            xml_path.write_text(new_text, encoding="utf-8")
            replaced += n
            print(f"  updated Txt356 in {xml_path.relative_to(decoded_dir)}")
    if replaced == 0:
        raise RuntimeError("Failed to find <string name=\"Txt356\"> in decoded strings.xml")


def replace_method_body(smali_text: str, method_sig_regex: str, new_body_lines: list[str]) -> str:
    pattern = re.compile(
        r"(^\.method[^\n]*\s" + method_sig_regex + r"\n)(.*?)(^\.end method)",
        re.MULTILINE | re.DOTALL,
    )
    m = pattern.search(smali_text)
    if not m:
        raise RuntimeError(f"Could not find method matching: {method_sig_regex}")
    replacement = m.group(1) + "\n".join(new_body_lines) + "\n" + m.group(3)
    return smali_text[: m.start()] + replacement + smali_text[m.end() :]


def patch_fr_favs(decoded_dir: Path):
    p = find_smali(decoded_dir, "com/delfan/madp/fragments/fr_favs.smali")
    text = p.read_text(encoding="utf-8")

    text = replace_method_body(
        text,
        r"onCreateView\(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;\)Landroid/view/View;",
        [
            "    .locals 2",
            "",
            "    sget v0, Lcom/delfan/madp/R$layout;->fr_favs:I",
            "    const/4 v1, 0x0",
            "    invoke-virtual {p1, v0, p2, v1}, Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;",
            "    move-result-object v0",
            "    invoke-static {p0, v0}, Lcom/delfan/madp/favs/OfflineFavsController;->onCreateView(Landroidx/fragment/app/Fragment;Landroid/view/View;)V",
            "    return-object v0",
        ],
    )

    text = replace_method_body(
        text,
        r"EmptyList_Frg5\(\)V",
        [
            "    .locals 0",
            "",
            "    invoke-static {}, Lcom/delfan/madp/favs/OfflineFavsController;->clearData()V",
            "    return-void",
        ],
    )

    text = replace_method_body(
        text,
        r"SetDataFavs\(Landroid/content/Context;\)V",
        [
            "    .locals 0",
            "",
            "    invoke-static {p0}, Lcom/delfan/madp/favs/OfflineFavsController;->refreshData(Landroid/content/Context;)V",
            "    return-void",
        ],
    )

    extra_methods = [
        (
            r"onResume\(\)V",
            ".method public onResume()V\n"
            "    .locals 0\n\n"
            "    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onResume()V\n"
            "    invoke-static {p0}, Lcom/delfan/madp/favs/OfflineFavsController;->onResume(Landroidx/fragment/app/Fragment;)V\n"
            "    return-void\n"
            ".end method",
        ),
        (
            r"onHiddenChanged\(Z\)V",
            ".method public onHiddenChanged(Z)V\n"
            "    .locals 0\n\n"
            "    invoke-super {p0, p1}, Landroidx/fragment/app/Fragment;->onHiddenChanged(Z)V\n"
            "    invoke-static {p0, p1}, Lcom/delfan/madp/favs/OfflineFavsController;->onHiddenChanged(Landroidx/fragment/app/Fragment;Z)V\n"
            "    return-void\n"
            ".end method",
        ),
        (
            r"onActivityResult\(IILandroid/content/Intent;\)V",
            ".method public onActivityResult(IILandroid/content/Intent;)V\n"
            "    .locals 0\n\n"
            "    invoke-super {p0, p1, p2, p3}, Landroidx/fragment/app/Fragment;->onActivityResult(IILandroid/content/Intent;)V\n"
            "    invoke-static {p0, p1, p2, p3}, Lcom/delfan/madp/favs/OfflineFavsController;->onActivityResult(Landroidx/fragment/app/Fragment;IILandroid/content/Intent;)V\n"
            "    return-void\n"
            ".end method",
        ),
    ]

    for sig_re, full_method in extra_methods:
        if re.search(r"^\.method[^\n]*\s" + sig_re, text, re.MULTILINE):
            body = full_method.split("\n", 1)[1].rsplit("\n.end method", 1)[0].split("\n")
            text = replace_method_body(text, sig_re, body)
        else:
            text = text.rstrip() + "\n\n" + full_method + "\n"

    p.write_text(text, encoding="utf-8")
    print(f"  patched {p.relative_to(decoded_dir)}")


def patch_activity_detials_video(decoded_dir: Path):
    p = find_smali(decoded_dir, "com/delfan/madp/Acts/activity_detials_video.smali")
    text = p.read_text(encoding="utf-8")

    # 1. Hook end of onCreate right after SavePrefLastView()V
    m_oc = re.search(
        r"(^\.method[^\n]*\sonCreate\(Landroid/os/Bundle;\)V\n)(.*?)(^\.end method)",
        text,
        re.MULTILINE | re.DOTALL,
    )
    if not m_oc:
        raise RuntimeError("onCreate(Bundle)V not found in activity_detials_video.smali")

    body = m_oc.group(2)
    m_loc = re.search(r"^\s*\.locals\s+(\d+)", body, re.MULTILINE)
    if m_loc:
        cur_locals = int(m_loc.group(1))
        if cur_locals < 5:
            body = body[: m_loc.start(1)] + "5" + body[m_loc.end(1) :]
    else:
        m_reg = re.search(r"^\s*\.registers\s+(\d+)", body, re.MULTILINE)
        if not m_reg:
            raise RuntimeError("Neither .locals nor .registers in onCreate")
        cur_regs = int(m_reg.group(1))
        if cur_regs < 7:
            body = body[: m_reg.start(1)] + "7" + body[m_reg.end(1) :]

    hook_target = re.compile(
        r"([ \t]*invoke-direct\s+\{p0\},\s*Lcom/delfan/madp/Acts/activity_detials_video;->SavePrefLastView\(\)V\s*\n"
        r"(?:[ \t]*(?:\.line[^\n]*|#[^\n]*)?\n)*)"
        r"([ \t]*return-void\s*\n)",
        re.MULTILINE,
    )
    m_ht = hook_target.search(body)
    if not m_ht:
        raise RuntimeError("Could not locate SavePrefLastView() + return-void in activity_detials_video.onCreate")

    on_create_hook = (
        "    move-object/from16 v4, p0\n"
        "    iget-object v0, v4, Lcom/delfan/madp/Acts/activity_detials_video;->videoid:Ljava/lang/String;\n"
        "    iget-object v1, v4, Lcom/delfan/madp/Acts/activity_detials_video;->title_Bundel:Ljava/lang/String;\n"
        "    iget-object v2, v4, Lcom/delfan/madp/Acts/activity_detials_video;->poster_Bundel:Ljava/lang/String;\n"
        "    iget-object v3, v4, Lcom/delfan/madp/Acts/activity_detials_video;->what:Ljava/lang/String;\n"
        "    invoke-static {v4, v0, v1, v2, v3}, Lcom/delfan/madp/favs/FavHook;->onDetailsCreate(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n"
        "    move-result-object v0\n"
        "    iput-object v0, v4, Lcom/delfan/madp/Acts/activity_detials_video;->Fav_State:Ljava/lang/String;\n\n"
    )
    body = body[: m_ht.end(1)] + on_create_hook + m_ht.group(2) + body[m_ht.end(2) :]
    text = text[: m_oc.start(2)] + body + text[m_oc.end(2) :]

    # 2. Add favOnDetailsLoaded()V helper
    helper_sig = r"favOnDetailsLoaded\(\)V"
    helper_method = (
        ".method public favOnDetailsLoaded()V\n"
        "    .locals 5\n\n"
        "    move-object/from16 v4, p0\n"
        "    iget-object v0, v4, Lcom/delfan/madp/Acts/activity_detials_video;->videoid:Ljava/lang/String;\n"
        "    iget-object v1, v4, Lcom/delfan/madp/Acts/activity_detials_video;->title_Bundel:Ljava/lang/String;\n"
        "    iget-object v2, v4, Lcom/delfan/madp/Acts/activity_detials_video;->poster_Bundel:Ljava/lang/String;\n"
        "    iget-object v3, v4, Lcom/delfan/madp/Acts/activity_detials_video;->what:Ljava/lang/String;\n"
        "    invoke-static {v4, v0, v1, v2, v3}, Lcom/delfan/madp/favs/FavHook;->onDetailsLoaded(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n"
        "    move-result-object v0\n"
        "    iput-object v0, v4, Lcom/delfan/madp/Acts/activity_detials_video;->Fav_State:Ljava/lang/String;\n"
        "    return-void\n"
        ".end method"
    )
    if re.search(r"^\.method[^\n]*\s" + helper_sig, text, re.MULTILINE):
        body_lines = helper_method.split("\n", 1)[1].rsplit("\n.end method", 1)[0].split("\n")
        text = replace_method_body(text, helper_sig, body_lines)
    else:
        text = text.rstrip() + "\n\n" + helper_method + "\n"

    p.write_text(text, encoding="utf-8")
    print(f"  patched {p.relative_to(decoded_dir)}")


def patch_details_fav_click(decoded_dir: Path):
    p = find_smali(decoded_dir, "com/delfan/madp/Acts/activity_detials_video$a.smali")
    text = p.read_text(encoding="utf-8")
    if "->a:Lcom/delfan/madp/Acts/activity_detials_video;" not in text:
        raise RuntimeError("Unexpected structure in activity_detials_video$a.smali")

    text = replace_method_body(
        text,
        r"onClick\(Landroid/view/View;\)V",
        [
            "    .locals 5",
            "",
            "    iget-object v0, p0, Lcom/delfan/madp/Acts/activity_detials_video$a;->a:Lcom/delfan/madp/Acts/activity_detials_video;",
            "    iget-object v1, v0, Lcom/delfan/madp/Acts/activity_detials_video;->videoid:Ljava/lang/String;",
            "    iget-object v2, v0, Lcom/delfan/madp/Acts/activity_detials_video;->title_Bundel:Ljava/lang/String;",
            "    iget-object v3, v0, Lcom/delfan/madp/Acts/activity_detials_video;->poster_Bundel:Ljava/lang/String;",
            "    iget-object v4, v0, Lcom/delfan/madp/Acts/activity_detials_video;->what:Ljava/lang/String;",
            "    invoke-static {v0, v1, v2, v3, v4}, Lcom/delfan/madp/favs/FavHook;->onDetailsFavClicked(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            "    return-void",
        ],
    )
    p.write_text(text, encoding="utf-8")
    print(f"  patched {p.relative_to(decoded_dir)}")


def patch_details_response_hook(decoded_dir: Path):
    p = find_smali(decoded_dir, "com/delfan/madp/Acts/activity_detials_video$d.smali")
    text = p.read_text(encoding="utf-8")

    m_method = re.search(
        r"(^\.method[^\n]*\sb\(Ljava/lang/String;\)V\n)(.*?)(^\.end method)",
        text,
        re.MULTILINE | re.DOTALL,
    )
    if not m_method:
        raise RuntimeError("b(String)V not found in activity_detials_video$d.smali")

    body = m_method.group(2)
    pat = re.compile(
        r"(^[ \t]*iget-object\s+([vp]\d+),\s*([vp]\d+),\s*Lcom/delfan/madp/Acts/activity_detials_video\$d;->b:Lcom/delfan/madp/Acts/activity_detials_video;\s*\n"
        r"(?:[ \t]*(?:\.line[^\n]*|#[^\n]*)?\n)*"
        r"[ \t]*iget-object\s+([vp]\d+),\s*\2,\s*Lcom/delfan/madp/Acts/activity_detials_video;->BtnAddFave_ADV:Landroid/widget/RelativeLayout;\s*\n"
        r"(?:[ \t]*(?:\.line[^\n]*|#[^\n]*)?\n)*"
        r"[ \t]*invoke-virtual\s+\{\4,\s*[vp]\d+\},\s*Landroid/view/View;->setVisibility\(I\)V\s*\n)",
        re.MULTILINE,
    )
    matches = list(pat.finditer(body))
    if len(matches) != 1:
        raise RuntimeError(f"Expected 1 BtnAddFave_ADV.setVisibility match in $d.b, found {len(matches)}")

    m = matches[0]
    reg_this = m.group(3)
    reg_btn = m.group(4)
    if reg_btn == reg_this:
        raise RuntimeError(f"Unexpected register aliasing reg_btn == reg_this ({reg_btn})")

    hook = (
        f"    iget-object {reg_btn}, {reg_this}, Lcom/delfan/madp/Acts/activity_detials_video$d;->b:Lcom/delfan/madp/Acts/activity_detials_video;\n"
        f"    invoke-virtual {{{reg_btn}}}, Lcom/delfan/madp/Acts/activity_detials_video;->favOnDetailsLoaded()V\n"
    )
    new_body = body[: m.end(1)] + hook + body[m.end(1) :]
    text = text[: m_method.start(2)] + new_body + text[m_method.end(2) :]
    p.write_text(text, encoding="utf-8")
    print(f"  patched {p.relative_to(decoded_dir)} (reg_this={reg_this}, reg_btn={reg_btn})")


def patch_title_lang_hooks(decoded_dir: Path):
    # 1. Intercept guest taps on DrMenu_RelChangeMovieTitle in activity_main$b.onClick -> TitleLangHook.toggleOffline
    p_btn = find_smali(decoded_dir, "com/delfan/madp/Acts/activity_main$b.smali")
    t_btn = p_btn.read_text(encoding="utf-8")
    m_btn_method = re.search(
        r"(^\.method[^\n]*\sonClick\(Landroid/view/View;\)V\n)(.*?)(^\.end method)",
        t_btn,
        re.MULTILINE | re.DOTALL,
    )
    if not m_btn_method:
        raise RuntimeError("onClick(View)V not found in activity_main$b.smali")
    btn_body = m_btn_method.group(2)
    m_loc = re.search(r"^([ \t]*\.locals\s+(\d+)[^\n]*\n)", btn_body, re.MULTILINE)
    if not m_loc:
        raise RuntimeError("Expected .locals directive in activity_main$b.onClick")
    if int(m_loc.group(2)) < 2:
        raise RuntimeError(f"Expected >= 2 locals in activity_main$b.onClick, got {m_loc.group(2)}")
    btn_guard = (
        "    sget-object v0, Lcom/delfan/madp/Other/b;->s:Ljava/lang/String;\n"
        "    const-string v1, \"T\"\n"
        "    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n"
        "    move-result v0\n"
        "    if-nez v0, :cond_logged_in_title_lang\n\n"
        "    iget-object v0, p0, Lcom/delfan/madp/Acts/activity_main$b;->a:Lcom/delfan/madp/Acts/activity_main;\n"
        "    invoke-static {v0}, Lcom/delfan/madp/favs/TitleLangHook;->toggleOffline(Landroid/app/Activity;)V\n"
        "    return-void\n\n"
        "    :cond_logged_in_title_lang\n"
    )
    new_btn_body = btn_body[: m_loc.end(1)] + btn_guard + btn_body[m_loc.end(1) :]
    t_btn = t_btn[: m_btn_method.start(2)] + new_btn_body + t_btn[m_btn_method.end(2) :]
    p_btn.write_text(t_btn, encoding="utf-8")
    print(f"  patched {p_btn.relative_to(decoded_dir)} (guest guard)")

    # 2. Re-apply saved guest title language after CheckUser updates b.j & b.s in activity_splash$b.b
    p_sp_cb = find_smali(decoded_dir, "com/delfan/madp/Acts/activity_splash$b.smali")
    t_sp_cb = p_sp_cb.read_text(encoding="utf-8")
    m_sp_cb = re.search(
        r"(^\.method[^\n]*\sb\(Ljava/lang/String;\)V\n)(.*?)(^\.end method)",
        t_sp_cb,
        re.MULTILINE | re.DOTALL,
    )
    if not m_sp_cb:
        raise RuntimeError("b(String)V not found in activity_splash$b.smali")
    sp_cb_body = m_sp_cb.group(2)
    pat_sp_cb = re.compile(
        r"([ \t]*sput-object\s+[vp]\d+,\s*Lcom/delfan/madp/Other/b;->f:Ljava/lang/String;\s*\n"
        r"(?:[ \t]*(?:\.line[^\n]*|\.catch[^\n]*|:try_end_[^\n]*|#[^\n]*)?\n)*)"
        r"([ \t]*const-string\s+(v\d+),\s*\"\"\s*\n)",
        re.MULTILINE,
    )
    m_sp_cb_matches = list(pat_sp_cb.finditer(sp_cb_body))
    if len(m_sp_cb_matches) != 1:
        raise RuntimeError(f"Expected 1 b.f -> const-string match in activity_splash$b.b, found {len(m_sp_cb_matches)}")
    m_sc = m_sp_cb_matches[0]
    reg_tmp = m_sc.group(3)
    if int(reg_tmp[1:]) > 15:
        raise RuntimeError(f"Expected 4-bit register for iget-object in activity_splash$b.b, got {reg_tmp}")
    sp_cb_hook = (
        f"    iget-object {reg_tmp}, p0, Lcom/delfan/madp/Acts/activity_splash$b;->a:Lcom/delfan/madp/Acts/activity_splash;\n"
        f"    invoke-static/range {{{reg_tmp} .. {reg_tmp}}}, Lcom/delfan/madp/favs/TitleLangHook;->applySavedIfGuest(Landroid/content/Context;)V\n"
    )
    new_sp_cb_body = sp_cb_body[: m_sc.end(1)] + sp_cb_hook + m_sc.group(2) + sp_cb_body[m_sc.end(2) :]
    t_sp_cb = t_sp_cb[: m_sp_cb.start(2)] + new_sp_cb_body + t_sp_cb[m_sp_cb.end(2) :]
    p_sp_cb.write_text(t_sp_cb, encoding="utf-8")
    print(f"  patched {p_sp_cb.relative_to(decoded_dir)} (reg_tmp={reg_tmp})")

    # 3. Restore guest title language early in activity_splash.onCreate
    p_sp = find_smali(decoded_dir, "com/delfan/madp/Acts/activity_splash.smali")
    t_sp = p_sp.read_text(encoding="utf-8")
    m_sp = re.search(
        r"(^\.method[^\n]*\sonCreate\(Landroid/os/Bundle;\)V\n)(.*?)(^\.end method)",
        t_sp,
        re.MULTILINE | re.DOTALL,
    )
    if not m_sp:
        raise RuntimeError("onCreate(Bundle)V not found in activity_splash.smali")
    sp_body = m_sp.group(2)
    pat_sp = re.compile(
        r"([ \t]*invoke-super\s+\{p0,\s*p1\},\s*Lcom/delfan/madp/Other/BaseActivitySave;->onCreate\(Landroid/os/Bundle;\)V\s*\n)",
        re.MULTILINE,
    )
    m_sp_matches = list(pat_sp.finditer(sp_body))
    if len(m_sp_matches) != 1:
        raise RuntimeError(f"Expected 1 BaseActivitySave.onCreate super call in activity_splash.onCreate, found {len(m_sp_matches)}")
    sp_hook = "    invoke-static/range {p0 .. p0}, Lcom/delfan/madp/favs/TitleLangHook;->applySavedIfGuest(Landroid/content/Context;)V\n"
    new_sp_body = sp_body[: m_sp_matches[0].end(1)] + sp_hook + sp_body[m_sp_matches[0].end(1) :]
    t_sp = t_sp[: m_sp.start(2)] + new_sp_body + t_sp[m_sp.end(2) :]
    p_sp.write_text(t_sp, encoding="utf-8")
    print(f"  patched {p_sp.relative_to(decoded_dir)}")

    # 4. Ensure guest title language is applied in activity_main.onCreate before drawer label check
    p_main = find_smali(decoded_dir, "com/delfan/madp/Acts/activity_main.smali")
    t_main = p_main.read_text(encoding="utf-8")
    m_main = re.search(
        r"(^\.method[^\n]*\sonCreate\(Landroid/os/Bundle;\)V\n)(.*?)(^\.end method)",
        t_main,
        re.MULTILINE | re.DOTALL,
    )
    if not m_main:
        raise RuntimeError("onCreate(Bundle)V not found in activity_main.smali")
    main_body = m_main.group(2)
    pat_main = re.compile(
        r"([ \t]*sput-object\s+[vp]\d+,\s*Lcom/delfan/madp/Other/b;->j:Ljava/lang/String;\s*\n)",
        re.MULTILINE,
    )
    m_main_matches = list(pat_main.finditer(main_body))
    if len(m_main_matches) != 1:
        raise RuntimeError(f"Expected 1 b.j write in activity_main.onCreate, found {len(m_main_matches)}")
    main_hook = "    invoke-static/range {p0 .. p0}, Lcom/delfan/madp/favs/TitleLangHook;->applySavedIfGuest(Landroid/content/Context;)V\n"
    new_main_body = main_body[: m_main_matches[0].end(1)] + main_hook + main_body[m_main_matches[0].end(1) :]
    t_main = t_main[: m_main.start(2)] + new_main_body + t_main[m_main.end(2) :]
    p_main.write_text(t_main, encoding="utf-8")
    print(f"  patched {p_main.relative_to(decoded_dir)}")


def inject_classes8_dex(unsigned_apk: Path, patch_dex: Path, out_apk: Path):
    with zipfile.ZipFile(unsigned_apk, "r") as zin:
        names = zin.namelist()
        if "classes8.dex" in names:
            raise RuntimeError("Base APK already contains classes8.dex!")
        with zipfile.ZipFile(out_apk, "w") as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zout.writestr(zi, data)
            zout.write(patch_dex, "classes8.dex", compress_type=zipfile.ZIP_DEFLATED)


def filter_abi_apk(base_apk: Path, abi: str, out_unsigned_apk: Path) -> bool:
    """Copy base_apk filtering lib/ to only `abi` if native libs exist; return True if native libs were present."""
    with zipfile.ZipFile(base_apk, "r") as zin:
        lib_entries = [n for n in zin.namelist() if n.startswith("lib/")]
        if not lib_entries or abi == "universal":
            shutil.copy2(base_apk, out_unsigned_apk)
            return bool(lib_entries)

        prefix = f"lib/{abi}/"
        with zipfile.ZipFile(out_unsigned_apk, "w") as zout:
            for info in zin.infolist():
                if info.filename.startswith("lib/") and not info.filename.startswith(prefix):
                    continue
                data = zin.read(info.filename)
                zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zout.writestr(zi, data)
        return True


def ensure_keystore(work_dir: Path, ks_path_arg, ks_pass, ks_alias, key_pass):
    if ks_path_arg:
        ks_path = Path(ks_path_arg)
        if not ks_path.is_file():
            raise RuntimeError(f"Keystore not found: {ks_path}")
        return ks_path, ks_pass, ks_alias, key_pass

    ks_path = work_dir / "ci-release.keystore"
    if not ks_path.is_file():
        keytool = shutil.which("keytool")
        if not keytool:
            raise RuntimeError("keytool not found in PATH")
        run(
            [
                keytool,
                "-genkeypair",
                "-v",
                "-keystore",
                str(ks_path),
                "-alias",
                ks_alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "10000",
                "-storepass",
                ks_pass,
                "-keypass",
                key_pass,
                "-dname",
                "CN=Mymoviebox Offline Favs, OU=Android, O=Community, L=Unknown, S=Unknown, C=IR",
            ]
        )
    return ks_path, ks_pass, ks_alias, key_pass


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Patch Mymoviebox APK with Offline Favorites & Folders")
    parser.add_argument("--input", default="apks/mymoviebox.apk")
    parser.add_argument("--work", default="build/patch-work")
    parser.add_argument("--out", default="dist")
    parser.add_argument("--apktool", default=None)
    parser.add_argument("--keystore", default=os.environ.get("ANDROID_KEYSTORE_PATH") or None)
    parser.add_argument("--ks-pass", default=os.environ.get("ANDROID_KEYSTORE_PASS") or "android")
    parser.add_argument("--ks-alias", default=os.environ.get("ANDROID_KEY_ALIAS") or "mymoviebox")
    parser.add_argument("--key-pass", default=os.environ.get("ANDROID_KEY_PASS") or "android")
    parser.add_argument("--abis", default="universal,arm64-v8a,armeabi-v7a,x86_64,x86")
    parser.add_argument("--version-label", default="v9")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    input_apk = (repo_root / args.input).resolve() if not Path(args.input).is_absolute() else Path(args.input)
    work_dir = (repo_root / args.work).resolve() if not Path(args.work).is_absolute() else Path(args.work)
    out_dir = (repo_root / args.out).resolve() if not Path(args.out).is_absolute() else Path(args.out)

    if not input_apk.is_file():
        raise RuntimeError(f"Input APK not found: {input_apk}")

    work_dir.mkdir(parents=True, exist_ok=True)
    out_dir.mkdir(parents=True, exist_ok=True)

    android_jar, d8, zipalign, apksigner = find_sdk_tools()
    apktool_path = find_apktool(args.apktool)
    ks_path, ks_pass, ks_alias, key_pass = ensure_keystore(
        work_dir, args.keystore, args.ks_pass, args.ks_alias, args.key_pass
    )

    print("1/6 Compiling offline favorites patch -> classes8.dex ...")
    patch_dex = compile_patch_dex(repo_root, work_dir, android_jar, d8)

    print("2/6 Decoding base APK with apktool ...")
    decoded_dir = work_dir / "decoded"
    if decoded_dir.exists():
        shutil.rmtree(decoded_dir)
    apktool_cmd = ["java", "-jar", str(apktool_path)] if apktool_path.suffix == ".jar" else [str(apktool_path)]
    run([*apktool_cmd, "d", "-f", str(input_apk), "-o", str(decoded_dir)])

    print("3/6 Overlaying custom layouts, drawables, and strings ...")
    overlay_resources(repo_root, decoded_dir)

    print("4/6 Patching smali hooks ...")
    patch_fr_favs(decoded_dir)
    patch_activity_detials_video(decoded_dir)
    patch_details_fav_click(decoded_dir)
    patch_details_response_hook(decoded_dir)
    patch_title_lang_hooks(decoded_dir)

    print("5/6 Rebuilding APK and injecting classes8.dex ...")
    rebuilt_apk = work_dir / "rebuilt-no-dex8.apk"
    patched_base_apk = work_dir / "patched-unsigned.apk"
    run([*apktool_cmd, "b", str(decoded_dir), "-o", str(rebuilt_apk)])
    inject_classes8_dex(rebuilt_apk, patch_dex, patched_base_apk)

    print("6/6 Aligning, signing, and verifying APKs ...")
    abis = [a.strip() for a in args.abis.split(",") if a.strip()]
    artifacts = []
    has_native_libs = False

    for abi in abis:
        abi_unsigned = work_dir / f"unsigned-{abi}.apk"
        abi_aligned = work_dir / f"aligned-{abi}.apk"
        abi_signed = out_dir / f"mymoviebox-offline-favs-{args.version_label}-{abi}.apk"
        has_native_libs = filter_abi_apk(patched_base_apk, abi, abi_unsigned)

        run([str(zipalign), "-f", "-p", "4", str(abi_unsigned), str(abi_aligned)])
        run(
            [
                str(apksigner),
                "sign",
                "--ks",
                str(ks_path),
                "--ks-pass",
                f"pass:{ks_pass}",
                "--ks-key-alias",
                ks_alias,
                "--key-pass",
                f"pass:{key_pass}",
                "--v1-signing-enabled",
                "true",
                "--v2-signing-enabled",
                "true",
                "--v3-signing-enabled",
                "true",
                "--out",
                str(abi_signed),
                str(abi_aligned),
            ]
        )
        run([str(apksigner), "verify", "--verbose", str(abi_signed)])
        digest = sha256_file(abi_signed)
        artifacts.append(
            {
                "abi": abi,
                "filename": abi_signed.name,
                "size_bytes": abi_signed.stat().st_size,
                "sha256": digest,
            }
        )

    sums_path = out_dir / "SHA256SUMS.txt"
    sums_lines = [f"{item['sha256']}  {item['filename']}\n" for item in artifacts]
    sums_path.write_text("".join(sums_lines), encoding="utf-8")

    manifest = {
        "input_apk": str(input_apk.name),
        "input_sha256": sha256_file(input_apk),
        "version_label": args.version_label,
        "pure_dalvik": not has_native_libs,
        "artifacts": artifacts,
    }
    (out_dir / "build-manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    print("\nBuild complete:")
    for item in artifacts:
        print(f"  {item['filename']} ({item['size_bytes']} bytes) sha256={item['sha256']}")


if __name__ == "__main__":
    main()
