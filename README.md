# Mymoviebox

An Android APK inspection and analysis workspace preconfigured with:

1. **REA (`morluto/rea` / `rea-agents`)** — CLI and MCP server for agent-driven static and runtime analysis across Android APKs, archives, native binaries, JavaScript bundles, and Flutter/Dart artifacts.
2. **Apktool (`ibotpeaches/apktool`)** — Android APK resource/manifest decoder and smali/resource rebuilder, checked out at `tools/apktool` and wrapped by `bin/apktool` for both standalone use and REA's `decode-android-resources` / `decode_android_resources` workflow.

---

## What Is Installed Right Now

| Component | Location | Status | Notes |
| --- | --- | --- | --- |
| **REA (`rea-agents` v6.7.0)** | `node_modules/.bin/rea` (and global `rea` in this sandbox) | **Ready** | 153 CLI commands, 207 MCP tools, 6 MCP prompts (`rea mcp doctor` passes). Compatible with Node.js `v22.22.3` (`^22.19.0 \|\| ^24.11.0 \|\| >=26.0.0`). |
| **REA Agent Skill** | `.agents/skills/reverse-engineer-anything/` | **Ready** | Official `reverse-engineer-anything` skill & references synced from `rea-agents@6.7.0`. |
| **Apktool Source Repo** | `tools/apktool/` (git submodule -> `ibotpeaches/apktool`) | **Checked out** | Shallow clone at commit `fbaeb47` (`3.1.0-SNAPSHOT` / `main`), including upstream launcher scripts under `tools/apktool/scripts/`. |
| **Apktool Wrapper** | `bin/apktool` | **Ready** | Resolves `java` + `apktool.jar` (from `tools/apktool-bin/` or Gradle build outputs) and works as `REA_APKTOOL_COMMAND`. |
| **Java Runtime / `apktool.jar`** | `tools/apktool-bin/apktool.jar` | **Host prerequisite** | Not present in this sandbox (no `java` installed and GitHub release CDN / Maven Central are outside the sandbox's outbound allowlist). See [Environment & Troubleshooting](docs/environment-and-troubleshooting.md). |

Verify your current environment at any time:

```bash
npm run verify:env
# or
bash scripts/check-env.sh
```

---

## Quick Start: Using With This Agent (or Any Coding Agent)

### 1. Supply your APK

Place your `.apk` inside `apks/` (gitignored for `*.apk`, `*.apks`, `*.xapk`, `*.aab` so binaries are not committed to Git):

```text
apks/your-app.apk
```

In this Arena chat, you can upload/attach the APK into `apks/` (or ask the agent to pull it from your connected Google Drive if you want to use Drive) and tell the agent what you want to investigate.

### 2. Ask the agent what to inspect

Examples of prompts you can give right away:

- *"Inspect `apks/app.apk` with `rea inspect-artifact` and summarize the package layout, DEX files, native `.so` libraries, and assets."*
- *"Check whether `apks/app.apk` is a Flutter app using `rea identify-flutter-build`."*
- *"Extract `apks/app.apk` safely into `analysis-output/app/` and inspect any embedded JS bundles or native libraries."*
- *"Using Apktool (`REA_APKTOOL_COMMAND=$PWD/bin/apktool`), decode `apks/app.apk` resources and list the `AndroidManifest.xml`, permissions, and localized string tables."* *(requires Java + `apktool.jar`)*
- *"Decompile class `com.example.MainActivity` in `apks/app.apk` using REA."* *(requires Java 17+ and `REA_JADX_MCP_JAR`)*

For complete agent workflows, MCP setup across Claude Code / Codex / Cursor / OpenCode / VS Code / Gemini CLI, and prompt patterns, see:

- **[Agent Usage Guide](docs/agent-usage.md)**
- **[Apktool Guide](docs/apktool-guide.md)**
- **[Environment & Troubleshooting](docs/environment-and-troubleshooting.md)**
- **[AGENTS.md](AGENTS.md)** (automatic instructions for coding agents)

---

## Workstation Setup (Full Apktool + Optional JADX / Latest REA)

If you clone this repository on your own machine (Linux/macOS) with JDK 17+ and unrestricted network access:

```bash
# 1. Install npm dependencies & initialize tools/apktool
npm install
git submodule update --init --depth 1 tools/apktool

# 2. Download verified official apktool_3.0.3.jar (SHA-256 checked)
bash scripts/install-tools.sh --download-apktool
# OR build Apktool from source in tools/apktool (requires JDK 17+)
bash scripts/install-tools.sh --build-apktool

# 3. Point REA to the repo's Apktool wrapper
export REA_APKTOOL_COMMAND="$PWD/bin/apktool"

# 4. Verify everything
npm run verify:env
```

Optional upgrades on a workstation with **Node.js `^22.23.4 || ^24.22.1 || >=26.12.1`**:

```bash
# Upgrade rea-agents to @latest (adds inspect-apk-signature, guided-workflows CLI, etc.)
bash scripts/install-tools.sh --upgrade-rea-latest

# Optionally clone the full morluto/rea source repository into tools/rea-src (gitignored)
bash scripts/install-tools.sh --with-rea-source
```

---

## Repository Layout

```text
Mymoviebox/
├── AGENTS.md                           # Instructions for AI coding agents working in this repo
├── CLAUDE.md                           # Entry pointer for Claude Code
├── README.md                           # Project overview & quick start
├── package.json                        # Pins rea-agents + helper npm scripts
├── bin/
│   └── apktool                         # Wrapper script (compatible with REA_APKTOOL_COMMAND)
├── scripts/
│   ├── install-tools.sh                # Workstation installer for rea-agents & apktool.jar
│   └── check-env.sh                    # Checks Node, Java, REA CLI/MCP, Apktool, JADX, ps
├── docs/
│   ├── agent-usage.md                  # How to use REA + Apktool via AI agents (CLI & MCP)
│   ├── apktool-guide.md                # Apktool standalone & REA resource decoding guide
│   ├── environment-and-troubleshooting.md
│   └── mcp.example.json                # Sample MCP server configuration
├── .agents/skills/
│   └── reverse-engineer-anything/      # Bundled REA agent skill & reference docs
├── tools/
│   ├── apktool/                        # Git submodule -> https://github.com/ibotpeaches/apktool
│   └── apktool-bin/                    # Gitignored location for apktool.jar
├── apks/                               # Gitignored directory for local APK inputs
├── analysis-output/                    # Gitignored directory for extracted artifacts
└── evidence/                           # Gitignored directory for saved REA Evidence JSON
```

---

## Responsible Use

Both `morluto/rea` (MIT) and `ibotpeaches/apktool` (Apache-2.0) are intended solely for lawful software analysis, debugging, interoperability, security auditing of software you own or are authorized to inspect, and educational research.
