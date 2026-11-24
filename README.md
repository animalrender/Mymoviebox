# Mymoviebox — Offline Favorites & Folders Patch

Android APK patch, reproducible build pipeline, and automated API 33 emulator E2E verification suite adding **100% offline Favorites, custom collections/folders, and JSON backup/restore** to `com.delfan.manNn` (`v9`, base APK `apks/mymoviebox.apk`).

---

## Features Implemented

1. **Zero-Login Offline Favorites (`BtnAddFave_ADV` on Details Screen)**
   - Works offline and without user login (`Other/b.g`, `Other/b.s` are no longer required).
   - Replaces `@string/Txt356` with clean Persian label **`افزودن به علاقه‌مندی`** (`در علاقه‌مندی‌ها (N)` when saved).
   - Reveals `BtnAddFave_ADV` immediately in `activity_detials_video.onCreate` and keeps it responsive even before/without network completion, prefilling saved description, release year, and IMDb rating when viewing a saved item offline.

2. **Collections / Folders Modal (`dialog_fav_collections.xml` + `FavCollectionsDialog`)**
   - Tapping the Details favorite button opens a bottom/center modal dialog allowing multi-folder assignment (`CheckBox` per folder), inline new folder creation (**ایجاد**), saving (**ذخیره علاقه‌مندی**), or removing (**حذف**).

3. **Offline Favorites Tab (`fr_favs.xml` + `OfflineFavsController` + `FolderAdapter`)**
   - Removes the login wall (`FrLayoutLogin_Frg5` hidden) and adds four RTL tabs matching the app's native light/night palette (`Other/b.r`, `Other/g.{o,m,p}`):
     - **همه** (All saved movies & series)
     - **سینمایی** (Movies)
     - **سریال** (Series)
     - **پوشه‌ها** (Custom folders with item counts, inline create/rename/delete, and folder drill-down view)
   - Clicking any saved card opens `activity_detials_video` directly with offline fallback metadata.

4. **Backup Export & Import (Android SAF)**
   - **خروجی** (`ACTION_CREATE_DOCUMENT`): exports a versioned JSON snapshot (`mymoviebox-favorites.json`, schema v1) containing all folders and saved items.
   - **ورودی** (`ACTION_OPEN_DOCUMENT`): imports and merges folders/items safely without losing existing local entries.
   - Persists synchronously in `SharedPreferences("offline_favorites_v1")` across app restarts (`am force-stop`).

---

## Repository Structure

```text
Mymoviebox/
├── apks/
│   └── mymoviebox.apk                  # Base APK (com.delfan.manNn v9)
├── patches/
│   ├── res/
│   │   ├── drawable/                   # Toolbar pill bg, folder badge bg, vector icons
│   │   └── layout/                     # Patched fr_favs.xml, item_fav_folder.xml, dialog_fav_collections.xml
│   ├── src/com/delfan/madp/favs/       # Runtime Java module compiled into classes8.dex
│   └── stubs/                          # Compile-only stubs matching original APK DEX signatures (incl. i.d)
├── scripts/
│   ├── patch-apk.py                    # Decodes APK, compiles classes8.dex, patches smali/res, aligns & signs APKs
│   ├── ci-emulator-test.py             # End-to-end adb/uiautomator emulator test suite (including offline & SAF)
│   ├── install-tools.sh                # Workstation installer for rea-agents & verified apktool 3.0.3
│   └── check-env.sh                    # Checks local environment tools
├── .github/workflows/
│   └── android-ci.yml                  # CI build + KVM API 33 emulator E2E + artifact/Check Run + GitHub Release
└── docs/                               # REA & Apktool reference documentation
```

---

## Reproducible Build

Requires **JDK 17+**, **Android SDK Build-Tools** (`d8`, `zipalign`, `apksigner`), and verified **Apktool 3.0.3** (`scripts/install-tools.sh --download-apktool`):

```bash
python3 scripts/patch-apk.py \
  --input apks/mymoviebox.apk \
  --work build/patch-work \
  --out dist \
  --apktool tools/apktool-bin/apktool.jar \
  --abis universal,arm64-v8a,armeabi-v7a,x86_64,x86 \
  --version-label v9
```

Optional release keystore flags: `--keystore path.jks --ks-pass ... --ks-alias ... --key-pass ...` (or set `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASS` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASS` in GitHub Actions Secrets). When omitted, a deterministic local/CI debug signing keystore is generated under `build/` (excluded from Git).

Outputs under `dist/`:
- `mymoviebox-offline-favs-v9-universal.apk`
- `mymoviebox-offline-favs-v9-arm64-v8a.apk`
- `mymoviebox-offline-favs-v9-armeabi-v7a.apk`
- `mymoviebox-offline-favs-v9-x86_64.apk`
- `mymoviebox-offline-favs-v9-x86.apk`
- `SHA256SUMS.txt` & `build-manifest.json`

---

## Automated CI, Versioning & API 33 Emulator Verification

`.github/workflows/android-ci.yml` automatically builds all 5 signed APKs on every push/PR and runs `scripts/ci-emulator-test.py` on a hardware-accelerated (`KVM`) API 33 Android emulator:

1. Fresh APK install & launch (`activity_splash` -> `activity_main`)
2. Offline Favorites empty state verification without any login dialog
3. Live movie & series Details inspection (`افزودن به علاقه‌مندی`, absence of `@string/Txt356` credit string)
4. Multi-folder creation, assignment, and removal via modal dialog
5. Verification of all 4 Favorites tabs (**همه / سینمایی / سریال / پوشه‌ها**), folder drill-down, and rename
6. Storage Access Framework (**خروجی / ورودی**) JSON export -> delete item -> import restore
7. Process kill persistence (`am force-stop`) and offline Details view with Wi-Fi + mobile data disabled
8. Logcat check confirming zero fatal runtime/Dalvik/resource exceptions

Every change merged or pushed to `main` automatically tags and publishes a new GitHub Release starting at **`v1.0.0`** and incrementing semantic patch versions (`v1.0.1`, `v1.0.2`, ...; include `[minor]` or `[major]` in a commit message to bump minor/major) with all 5 verified APKs (`universal` + per-ABI) and `SHA256SUMS.txt` attached.
