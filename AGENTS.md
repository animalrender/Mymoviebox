# Agent Instructions (`AGENTS.md`)

This repository is set up for **agent-driven Android APK and binary inspection** using:

- **REA (`rea-agents`)**: Installed locally in `node_modules/.bin/rea` (and on `PATH` when installed globally).
- **Apktool (`ibotpeaches/apktool`)**: Checked out as a submodule at `tools/apktool` and wrapped by `bin/apktool`.
- **Bundled REA Skill**: `.agents/skills/reverse-engineer-anything/SKILL.md` (and `references/`).

Follow the guidelines below whenever assisting the user with an APK or binary in this workspace.

---

## 1. Check Available Capabilities Before Running External Engines

Always verify what is available on the current host before promising or running engine-backed commands:

```bash
bash scripts/check-env.sh
```

Specifically:
- **Built-in REA tools that need NO Java or external decompilers** (work in restricted sandboxes too):
  - `rea inspect-artifact <apk>` — ZIP/APK inventory, central-directory entry digests, format classification.
  - `rea extract-artifact <apk>` — safe extraction of regular archive members.
  - `rea identify-flutter-build <apk>` — Dart snapshot hash & Flutter engine provenance by pure APK parsing.
  - `rea inspect-dart-aot <path>` — Dart AOT snapshot sections, headers, and string pool.
  - `rea analyze-javascript-application <dir>` — static analysis for extracted JS/React Native/Cordova/Electron bundles.
  - `rea inspect-go-binary <elf>` / `rea inspect-rust-binary <elf>` / `rea inspect-binary-layout <elf>` — static native library metadata.
- **Tools that require host prerequisites**:
  - **Apktool** (`rea decode-android-resources <apk>` or `./bin/apktool d <apk>`): requires `java` (JRE 8+) and `apktool.jar` (in `tools/apktool-bin/apktool.jar` or built in `tools/apktool`). Pass `REA_APKTOOL_COMMAND="$PWD/bin/apktool"`.
  - **JADX** (`rea inspect-android-package`, `search-android-classes`, `inspect-android-class`, `inspect-android-method`, `trace-android-references`): requires JDK 17+ (`JAVA_HOME`) and `REA_JADX_MCP_JAR`.
  - **Native decompilers** (`rea open-binary`, `decompile`, `function`): require Ghidra (`GHIDRA_INSTALL_DIR`), Hopper (`HOPPER_LAUNCHER_PATH`), IDA (`REA_IDA_MCP_CONFIG`), or Kuna (`REA_KUNA_COMMAND`).

If `java`, `apktool.jar`, or `REA_JADX_MCP_JAR` is missing in the current environment, report that clearly instead of guessing or fabricating output, and continue with the built-in static inspection tools that **are** available.

---

## 2. Recommended APK Triage Workflow

1. **Read `.agents/skills/reverse-engineer-anything/SKILL.md`** and `.agents/skills/reverse-engineer-anything/references/android-applications.md` when doing deep REA work.
2. **Inventory first (no execution)**:
   ```bash
   rea inspect-artifact apks/target.apk --format json > evidence/artifact-inventory.json
   ```
   Summarize large outputs cleanly with `rea inspect-analysis-view` (passing the embedded `inventory_artifact` Evidence JSON via a view request file):
   ```bash
   python3 -c '
   import json
   ev = json.load(open("evidence/artifact-inventory.json"))
   json.dump({"source": {"kind": "inline", "evidence": ev["normalized_result"]["substeps"][0]["evidence"]}, "view": {"kind": "summary"}}, open("evidence/artifact-view.json", "w"))
   '
   rea inspect-analysis-view evidence/artifact-view.json --format json
   ```
   Or quickly inspect top-level counts / paths with `--filter-output`:
   ```bash
   rea inspect-artifact apks/target.apk --filter-output normalized_result.observations --format json
   ```
3. **Detect framework**:
   - Check `rea identify-flutter-build apks/target.apk --format json` if `libflutter.so` / `libapp.so` appears.
   - Check `assets/index.android.bundle` (React Native) or `assets/www/` (Cordova/Capacitor) or `lib/<abi>/libil2cpp.so` + `assets/bin/Data/Managed/Metadata/global-metadata.dat` (Unity IL2CPP).
4. **Decode manifest & resources with Apktool** (when Java + `apktool.jar` are available):
   ```bash
   REA_APKTOOL_COMMAND="$PWD/bin/apktool" rea decode-android-resources apks/target.apk --format json > evidence/apktool-resources.json
   ```
   Or decode to disk in `analysis-output/`:
   ```bash
   ./bin/apktool d -s -f -o analysis-output/target-res apks/target.apk
   ```
   (`-s` / `--no-src` skips smali disassembly for faster resource/manifest inspection; omit `-s` when smali inspection is needed.)
5. **Inspect DEX / Java / Kotlin classes** (when `REA_JADX_MCP_JAR` + JDK 17+ are configured):
   - `rea inspect-android-package apks/target.apk --format json`
   - `rea search-android-classes apks/target.apk "<query>"`
   - `rea inspect-android-class apks/target.apk "<class>"`
   - `rea inspect-android-method apks/target.apk "<class>" "<method>"`

---

## 3. Evidence Hygiene & Boundaries

- Keep all APK files under `apks/`, decoded trees under `analysis-output/`, and REA JSON records under `evidence/` (all gitignored).
- Distinguish **observed facts** (SHA-256 digests, manifest XML attributes, symbol tables, returned REA Evidence IDs) from **inferences** and **unknowns**.
- Preserve REA `evidence_id`, `limitations`, and `coverage` fields in summaries.
- Only analyze software the user owns or is authorized to examine; do not assist with piracy, DRM circumvention, credential theft, or exploit development.
