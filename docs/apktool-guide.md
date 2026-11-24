# Apktool Guide (Standalone & Through REA)

This repository includes `ibotpeaches/apktool` as a git submodule under `tools/apktool/` and exposes a workspace wrapper at `bin/apktool`.

---

## 1. Preparing `apktool.jar`

`tools/apktool/` contains the upstream source repository (including `scripts/linux/apktool`, `scripts/osx/apktool`, and `scripts/windows/apktool.bat`), while the compiled `apktool.jar` binary requires Java to run (or JDK 17+ to build).

On a workstation with Java installed:

### Option A — Download the verified official release JAR (recommended)
```bash
bash scripts/install-tools.sh --download-apktool
```
This downloads `apktool_3.0.3.jar` from `iBotPeaches/Apktool` releases, verifies its SHA-256 checksum (`dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423`), and places it at `tools/apktool-bin/apktool.jar` (gitignored).

### Option B — Build directly from `tools/apktool` source
Requires **JDK 17+** (`javac`) and Gradle network access to Maven Central / Google Maven / Gradle distribution servers:
```bash
bash scripts/install-tools.sh --build-apktool
```
This runs `./gradlew build shadowJar proguard` inside `tools/apktool/` and copies the resulting `apktool_*.jar` to `tools/apktool-bin/apktool.jar`.

---

## 2. Using Apktool Through REA (Recommended for Agents)

REA integrates Apktool via `REA_APKTOOL_COMMAND`:

```bash
export REA_APKTOOL_COMMAND="$PWD/bin/apktool"

# 1. Verify launcher and version
rea inspect-apktool-client --format json

# 2. Decode manifest, apktool.yml metadata, and default string table
rea decode-android-resources apks/app.apk --format json > evidence/app-resources.json

# 3. Decode without string tables (fastest manifest + metadata view)
rea decode-android-resources apks/app.apk --no-strings --format json

# 4. Decode multiple localized string tables in one pass
rea decode-android-resources apks/app.apk --locales de --locales pt-BR --format json
```

### How REA invokes Apktool
- REA copies the APK into a temporary private workspace, records its SHA-256 digest (`target.sha256`), and runs `apktool d --no-src` (skipping smali by default for speed and safety).
- The temporary directory is automatically cleaned up after decoding.
- Note: On `rea-agents@6.7.0`, `decode-android-resources` supports `--locale`, `--locales`, and `--strings` / `--no-strings`. (Newer `rea-agents@6.10.1+` adds `--string-tables-only`, `--string-offset`, `--string-limit`, `--decode-timeout-ms`, and byte-budget flags.)

---

## 3. Standalone Apktool Commands (Apktool 3.x)

When you want full decoded files on disk (including smali code or rebuilding):

```bash
# Check version & help
./bin/apktool --version
./bin/apktool --help

# Decode APK (resources + smali) into analysis-output/app-decoded
./bin/apktool d -f -o analysis-output/app-decoded apks/app.apk

# Decode resources + manifest only (skip DEX/smali disassembly — much faster)
./bin/apktool d -s -f -o analysis-output/app-res apks/app.apk

# Decode ONLY AndroidManifest.xml (skip resources and smali)
./bin/apktool d -s --only-manifest -f -o analysis-output/app-manifest apks/app.apk

# Decode DEX/smali only (skip resources)
./bin/apktool d -r -f -o analysis-output/app-smali apks/app.apk

# Rebuild a modified decoded directory back into an APK
./bin/apktool b -o analysis-output/rebuilt.apk analysis-output/app-decoded

# Pass extra JVM heap memory (e.g., 4 GiB for very large APKs)
./bin/apktool -JXmx4096M d -f -o analysis-output/app-decoded apks/app.apk
```

### Framework APK management (for OEM / system APKs)
If decoding a vendor/system APK fails due to missing framework resources:

```bash
./bin/apktool if path/to/framework-res.apk
./bin/apktool lf   # list installed frameworks
./bin/apktool cf   # clean cached frameworks
```

---

## 4. Security Advisories Note

REA discloses known Apktool advisory windows during `inspect-apktool-client`:
- `CVE-2024-21633` (versions before `2.9.2`)
- `GHSA-vgwr-4w3p-xmjv` (Windows, before `2.9.3`)
- `CVE-2026-39973` / `GHSA-m8mh-x359-vm8m` (`3.0.0` through `< 3.0.2`)

Always use **Apktool `3.0.3` or newer** (or `tools/apktool` `main` >= `3.1.0-SNAPSHOT`), especially when inspecting untrusted APKs.
