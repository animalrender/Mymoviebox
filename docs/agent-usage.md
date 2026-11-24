# Using REA and Apktool via AI Agents

This guide explains how to use **REA (`morluto/rea`)** and **Apktool (`ibotpeaches/apktool`)** together through AI coding agents — both **directly via CLI/shell tools** (such as Arena.ai Agent Mode or a bash-enabled agent) and **via MCP (Model Context Protocol)** (Claude Code, Codex, Cursor, OpenCode, Gemini CLI, VS Code, etc.).

---

## Mode 1: Direct Agent CLI Usage (Works Immediately in This Repo)

Any agent with shell/bash access can invoke REA and Apktool directly inside this repository without setting up an MCP server first.

### Why this works out of the box
1. `rea-agents` is installed in `node_modules/.bin/rea` (and on `PATH` if installed globally).
2. `.agents/skills/reverse-engineer-anything/SKILL.md` and `AGENTS.md` are checked into the repo so agents automatically know the workflow.
3. `./bin/apktool` provides a stable launcher path for `REA_APKTOOL_COMMAND`.

### Example prompts you can give the agent

1. **First-pass APK triage (works with zero external Java/JADX dependencies):**
   > *"Inspect `apks/myapp.apk` with `rea inspect-artifact --format json`, save the output to `evidence/myapp-artifact.json`, and summarize its structure, native ABIs, DEX count, and detected frameworks (Flutter, React Native, Unity, etc.)."*

2. **Flutter / Dart snapshot inspection (pure APK parsing, no Java needed):**
   > *"Run `rea identify-flutter-build apks/myapp.apk --format json` and check if Dart AOT snapshot metadata is present."*

3. **Resource & Manifest decoding via Apktool (requires Java + `apktool.jar`):**
   > *"Run `REA_APKTOOL_COMMAND=$PWD/bin/apktool rea decode-android-resources apks/myapp.apk --format json` (and `--locales de --locales es` if localized), then summarize package name, SDK versions, permissions, and exported components."*

4. **Smali + Resource extraction to disk via Apktool (requires Java + `apktool.jar`):**
   > *"Decode `apks/myapp.apk` into `analysis-output/myapp` using `./bin/apktool d -f -o analysis-output/myapp apks/myapp.apk` and inspect `AndroidManifest.xml` and `res/xml/network_security_config.xml`."*

5. **Java / Kotlin class & method inspection via JADX (requires JDK 17+ and `REA_JADX_MCP_JAR`):**
   > *"Run `rea inspect-android-package apks/myapp.apk`, search classes matching `ApiClient`, and decompile its request builder method."*

---

## Mode 2: Connecting REA as an MCP Server

REA exposes **207 MCP tools** and **6 guided MCP prompts** over stdio (and HTTP with `--http`). Connecting REA over MCP lets the agent call structured tools like `inspect_artifact`, `decode_android_resources`, `inspect_apktool_client`, `inspect_android_package`, and `inspect_analysis_view` with retained in-session Evidence.

### Automated setup with `rea setup`

Preview what `rea setup` proposes before writing any user config files:

```bash
# Replace <client_id> with: claude_code, codex, cursor, claude_desktop, opencode,
# gemini_cli, windsurf, vscode, copilot_cli, devin, etc.
npx rea --version
npx rea setup --client <client_id> --dry-run --json
```

Apply after reviewing the dry-run plan:

```bash
npx rea setup --client <client_id> --yes
```

Then restart/reconnect your agent client and verify with:

```bash
npx rea doctor --client <client_id> --json
npx rea mcp doctor --format json
```

### Project-scoped Claude Code registration (recommended for this repo)

To register REA **only** for this repository in Claude Code (without writing `.mcp.json` into Git or enabling REA globally), run from the repository root with `CLAUDE_CONFIG_DIR` unset:

```bash
REA_APKTOOL_COMMAND="$PWD/bin/apktool" \
  claude mcp add --scope local \
  --env "REA_APKTOOL_COMMAND=$PWD/bin/apktool" \
  rea -- "$(command -v node)" "$PWD/node_modules/rea-agents/scripts/rea.mjs" mcp
```

Verify inside Claude Code or with `claude mcp list`. Remove anytime with:

```bash
claude mcp remove --scope local rea
```

### Manual MCP configuration (Codex / Cursor / generic JSON clients)

See [`docs/mcp.example.json`](mcp.example.json) for a generic JSON entry:

```json
{
  "mcpServers": {
    "rea": {
      "command": "npx",
      "args": ["-y", "rea-agents@6.7.0", "--mcp"],
      "env": {
        "REA_APKTOOL_COMMAND": "/ABSOLUTE/PATH/TO/Mymoviebox/bin/apktool"
      }
    }
  }
}
```

For **Codex** (`~/.codex/config.toml`):

```toml
[mcp_servers.rea]
command = "node"
args = ["/ABSOLUTE/PATH/TO/Mymoviebox/node_modules/rea-agents/scripts/rea.mjs", "mcp"]

[mcp_servers.rea.env]
REA_APKTOOL_COMMAND = "/ABSOLUTE/PATH/TO/Mymoviebox/bin/apktool"
```

If you also configure JADX or Ghidra on your workstation, add `REA_JADX_MCP_JAR`, `JAVA_HOME`, or `GHIDRA_INSTALL_DIR` to the same `env` block.

---

## Mode 3: Key MCP Tools & Prompts for Android APKs

When connected via MCP, an agent can use these primary tools and prompts:

### Core MCP Tools
| MCP Tool | Purpose | Prerequisites |
| --- | --- | --- |
| `binary_session` | Inspect session state and `tool_availability` | None |
| `inspect_artifact` / `extract_artifact` | ZIP/APK central-directory inventory, SHA-256 digests, safe extraction | None |
| `identify_flutter_build` / `inspect_dart_aot` | Dart snapshot hash, engine provenance, AOT string pool | None |
| `inspect_apktool_client` | Verify configured Apktool launcher & version | `bin/apktool` + Java + `apktool.jar` |
| `decode_android_resources` | Decode manifest, `apktool.yml`, and paged string tables | `bin/apktool` + Java + `apktool.jar` |
| `inspect_android_package` | Manifest declarations, components, permissions, SDKs | `REA_JADX_MCP_JAR` + JDK 17+ |
| `search_android_classes` | Search APK classes by name | `REA_JADX_MCP_JAR` + JDK 17+ |
| `inspect_android_class` | List fields & methods (with overload indices) of a class | `REA_JADX_MCP_JAR` + JDK 17+ |
| `inspect_android_method` | Decompile a specific method | `REA_JADX_MCP_JAR` + JDK 17+ |
| `trace_android_references` | Find incoming static references to a class or method | `REA_JADX_MCP_JAR` + JDK 17+ |
| `project_android_application_graph` | Project APK inventory Evidence into an application graph | Retained/exported APK inventory Evidence |
| `inspect_analysis_view` | Read compact summaries or paged subsets of large Evidence JSON | Saved Evidence file or retained MCP `evidence_id` |

*(If you upgrade to `rea-agents@6.10.1+` on Node `>=22.23.4`, you also gain `inspect_apk_signature`, `inspect_il2cpp_metadata`, and `inspect_android_jni_bindings`.)*

### Guided MCP Prompts
REA exposes 6 built-in MCP prompts (`prompts/list` / `prompts/get`):
- `investigate_feature` — trace a feature from discovery to implementation evidence
- `compare_application_versions` — compare two versions of an application
- `verify_reconstruction` — evaluate a reconstruction against retained Evidence
- `trace_crash` — correlate crash symptoms with static code & capture evidence
- `audit_residual_unknowns` — audit unresolved unknowns in an investigation
- `prepare_bounded_process_capture` — plan a bounded process capture experiment

---

## Handling Large Evidence Outputs Without Overflowing Agent Context

REA's `inspect-artifact` and `decode-android-resources` outputs can be large on real-world APKs. Follow the summary-first pattern:

```bash
# 1. Save full Evidence JSON to disk
rea inspect-artifact apks/app.apk --format json > evidence/app-artifact.json

# 2. Extract the inner inventory_artifact Evidence and project a compact summary view
python3 -c '
import json
ev = json.load(open("evidence/app-artifact.json"))
req = {"source": {"kind": "inline", "evidence": ev["normalized_result"]["substeps"][0]["evidence"]}, "view": {"kind": "summary"}}
open("evidence/app-view.json", "w").write(json.dumps(req))
'
rea inspect-analysis-view evidence/app-view.json --format json
```

*(Note: on `rea-agents@6.7.0`, CLI `inspect-analysis-view` takes a JSON file path or inline JSON string with `"source": {"kind": "inline", ...}`; `"evidence-file"` and `-` stdin were added in newer releases.)*

You can also use REA's global output flags on any CLI command:
- `--filter-output normalized_result.subject` — keep only specific JSON paths
- `--token-count` — check token size before reading
- `--token-limit 2000 --token-offset 0` — paginate output by tokens
- `--format jsonl` — stream row collections one JSON record per line
