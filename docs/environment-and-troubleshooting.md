# Environment, Runtime Matrix & Troubleshooting

## 1. Runtime Compatibility Matrix

| Feature / Tool | Requirement | Status in Current Sandbox | Status on Standard Workstation |
| --- | --- | --- | --- |
| **REA CLI & MCP (`rea-agents@6.7.0`)** | Node.js `^22.19.0 \|\| ^24.11.0 \|\| >=26.0.0`, `procps` `ps` | **Working** (`node v22.22.3`, `/usr/bin/ps`) | **Working** |
| **REA Built-in Artifact / Flutter / JS / ELF / .NET tools** | `rea-agents` only (pure JS / built-in workers) | **Working** | **Working** |
| **REA Latest (`rea-agents@6.10.1+` / `morluto/rea` `main`)** | Node.js `^22.23.4 \|\| ^24.22.1 \|\| >=26.12.1` | Blocked on `v22.22.3` (rejected by `scripts/lib/runtime-admission.mjs`) | **Working** after upgrading Node.js |
| **Apktool (`bin/apktool` & `rea decode-android-resources`)** | Java 8+ (`java`), plus `apktool.jar` (3.0.3+ or built via JDK 17+) | `tools/apktool` source cloned; `java` & `apktool.jar` absent due to container image / egress allowlist | **Working** after installing JDK & running `scripts/install-tools.sh --download-apktool` |
| **REA Java/Kotlin Decompilation (`inspect-android-*`)** | JDK 17+ (`JAVA_HOME`) + `REA_JADX_MCP_JAR` (`jadx-headless-mcp` JAR) | Optional, not installed | **Working** once `REA_JADX_MCP_JAR` is supplied |
| **REA SQLite Inspector (`inspect-sqlite-database`)** | Node.js `24.x (>=24.15)` or `26+` | Unavailable on Node 22.x | **Working** on Node 24.15+ / 26+ |
| **REA Native Decompilers (`open-binary` / `decompile`)** | Ghidra (`GHIDRA_INSTALL_DIR`), Hopper, IDA, or Kuna | Optional, not configured | **Working** once configured |

---

## 2. Why `rea-agents@6.7.0` Is Pinned in `package.json`

`morluto/rea` raised its minimum Node.js floor in commit `28a33cae` (`fix(runtime)!: require patched Node.js security releases`, released in `rea-agents@6.8.0+`) to:

```text
^22.23.4 || ^24.22.1 || >=26.12.1
```

Because this sandbox runs **Node.js `v22.22.3`**, `rea-agents@6.8.0+` refuses to start with an unsupported runtime message. Pinning **`rea-agents@6.7.0`** ensures:
- `npm install` and `rea` run cleanly on **both** `v22.22.3` and newer Node `22.x / 24.x / 26.x` releases (`^22.19.0 || ^24.11.0 || >=26.0.0`).
- All core APK/Android/Flutter/Artifact/JS/MCP workflows (`153` CLI commands, `207` MCP tools, `6` MCP prompts) work natively.

When running on a workstation with Node.js `>=22.23.4`, `>=24.22.1`, or `>=26.12.1`, you can upgrade anytime:

```bash
bash scripts/install-tools.sh --upgrade-rea-latest
```

---

## 3. Why `apktool.jar` Is Not Prebuilt in Restricted Sandboxes

In restricted cloud sandboxes where:
1. No JDK/JRE is preinstalled (`java: command not found`), and
2. Outbound network access is allowlisted to Git/package registries (`github.com`, `codeload.github.com`, `api.github.com`, `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org`),

the following external hosts required to fetch or compile `apktool.jar` are unreachable:
- `objects.githubusercontent.com` (where GitHub Release `.jar` downloads redirect)
- `deb.debian.org` (OS package installation for OpenJDK)
- `services.gradle.org`, `repo.maven.apache.org`, `dl.google.com` (Gradle wrapper & Java build dependencies)

In that environment, `tools/apktool` (the full `ibotpeaches/apktool` Git checkout) and `bin/apktool` are ready in the repository, and REA's pure-JS/built-in APK inspection commands (`inspect-artifact`, `extract-artifact`, `identify-flutter-build`, `inspect-dart-aot`, `analyze-javascript-application`) still run normally.

On any normal workstation with Java 17+ and standard internet access, run:

```bash
bash scripts/install-tools.sh --download-apktool
```

---

## 4. Common Troubleshooting

### `The selected apktool launcher was not found` or `did not report a usable version`
1. Run `bash scripts/check-env.sh` to see whether `java` or `apktool.jar` is missing.
2. Ensure `REA_APKTOOL_COMMAND` points to the **absolute** path of `bin/apktool`:
   ```bash
   export REA_APKTOOL_COMMAND="$PWD/bin/apktool"
   ```
3. Verify manually:
   ```bash
   "$REA_APKTOOL_COMMAND" --version
   ```

### `Set REA_JADX_MCP_JAR to the absolute path of a caller-supplied jadx-headless-mcp ... JAR`
- REA never downloads `jadx-headless-mcp` or a JDK automatically.
- Download `jadx-headless-mcp` separately, install JDK 17+, and export:
  ```bash
  export JAVA_HOME=/path/to/jdk-17
  export REA_JADX_MCP_JAR=/absolute/path/to/jadx-headless-mcp.jar
  ```

### `OutOfMemoryError` when decoding a large APK with Apktool
- For standalone runs, pass a larger JVM heap via `-J`:
  ```bash
  ./bin/apktool -JXmx4096M d -f -o analysis-output/app apks/large.apk
  ```
- For REA (`REA_APKTOOL_COMMAND`), either edit the default `-Xmx1024M` in `bin/apktool` or export a custom wrapper script with `-Xmx4096M`.

### `ps` prerequisite error on Linux
- REA's process supervisor inspects `/bin/ps` or `/usr/bin/ps` on Linux (`procps` / `procps-ng`). Verify with `which ps` or `bash scripts/check-env.sh`.
