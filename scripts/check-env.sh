#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

REA_BIN=""
if [ -x "$REPO_ROOT/node_modules/.bin/rea" ]; then
  REA_BIN="$REPO_ROOT/node_modules/.bin/rea"
elif command -v rea >/dev/null 2>&1; then
  REA_BIN="$(command -v rea)"
fi

echo "=== Mymoviebox Tooling Check ==="
echo "Repo root: $REPO_ROOT"
echo "Node.js:   $(node -v 2>/dev/null || echo 'missing')"
echo "npm:       $(npm -v 2>/dev/null || echo 'missing')"

if command -v java >/dev/null 2>&1; then
  echo "Java:      $(java -version 2>&1 | head -n 1)"
else
  echo "Java:      missing (required to run apktool.jar / JADX)"
fi

if command -v ps >/dev/null 2>&1; then
  echo "ps:        $(command -v ps)"
else
  echo "ps:        missing (procps required by REA Linux process supervisor)"
fi

if [ -n "$REA_BIN" ]; then
  echo "REA CLI:   $REA_BIN (v$("$REA_BIN" --version 2>/dev/null || echo 'unknown'))"
else
  echo "REA CLI:   missing (run: npm install)"
fi

if [ -f "$REPO_ROOT/tools/apktool/build.gradle.kts" ]; then
  echo "Apktool repo: present at tools/apktool ($(git -C tools/apktool rev-parse --short HEAD 2>/dev/null || echo 'unknown'))"
else
  echo "Apktool repo: not initialized (run: git submodule update --init --depth 1 tools/apktool)"
fi

if [ -r "$REPO_ROOT/tools/apktool-bin/apktool.jar" ]; then
  echo "Apktool JAR:  present at tools/apktool-bin/apktool.jar"
else
  echo "Apktool JAR:  missing (run: bash scripts/install-tools.sh --download-apktool on a host with JDK & GitHub CDN access)"
fi

if [ -n "${REA_JADX_MCP_JAR:-}" ] && [ -r "${REA_JADX_MCP_JAR}" ]; then
  echo "JADX MCP JAR: $REA_JADX_MCP_JAR"
else
  echo "JADX MCP JAR: not configured (optional: set REA_JADX_MCP_JAR=/abs/path/to/jadx-headless-mcp.jar)"
fi

if [ -n "$REA_BIN" ]; then
  echo ""
  echo "--- REA MCP Doctor ---"
  "$REA_BIN" mcp doctor --format json | python3 -c "
import json, sys
d = json.load(sys.stdin)
print('healthy:', d.get('healthy'), '| server:', d.get('server'), '| tools:', d.get('inventory', {}).get('tools', {}).get('observed'), '| prompts:', d.get('inventory', {}).get('prompts', {}).get('observed'))
"
  echo ""
  echo "--- REA Apktool Client Probe ---"
  REA_APKTOOL_COMMAND="${REA_APKTOOL_COMMAND:-$REPO_ROOT/bin/apktool}" "$REA_BIN" inspect-apktool-client --format json || true
fi
