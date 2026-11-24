#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APKTOOL_VERSION="3.0.3"
APKTOOL_URL="https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar"
APKTOOL_SHA256="dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"

DO_DOWNLOAD_APKTOOL=false
DO_BUILD_APKTOOL=false
DO_WITH_REA_SOURCE=false
DO_UPGRADE_REA=false

usage() {
  cat <<'EOF'
Usage: bash scripts/install-tools.sh [options]

Installs and verifies REA (morluto/rea) and Apktool (ibotpeaches/apktool) in this repository.

Options:
  --download-apktool   Download official apktool_3.0.3.jar (verified by SHA-256) into tools/apktool-bin/
  --build-apktool      Build apktool.jar from source in tools/apktool using Gradle (requires JDK 17+)
  --with-rea-source    Clone https://github.com/morluto/rea.git into tools/rea-src (gitignored)
  --upgrade-rea-latest Upgrade to rea-agents@latest (requires Node >=22.23.4, >=24.22.1, or >=26.12.1)
  -h, --help           Show this help message
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --download-apktool) DO_DOWNLOAD_APKTOOL=true ;;
    --build-apktool) DO_BUILD_APKTOOL=true ;;
    --with-rea-source) DO_WITH_REA_SOURCE=true ;;
    --upgrade-rea-latest) DO_UPGRADE_REA=true ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

cd "$REPO_ROOT"

echo "[1/4] Checking Node.js and installing npm dependencies (rea-agents)..."
if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "Error: Node.js and npm are required." >&2
  exit 1
fi
npm install

echo "[2/4] Syncing bundled REA agent skill to .agents/skills/reverse-engineer-anything..."
if [ -d "node_modules/rea-agents/skills/reverse-engineer-anything" ]; then
  mkdir -p ".agents/skills"
  rm -rf ".agents/skills/reverse-engineer-anything"
  cp -R "node_modules/rea-agents/skills/reverse-engineer-anything" ".agents/skills/"
fi

if [ "$DO_UPGRADE_REA" = true ]; then
  echo "[optional] Installing rea-agents@latest..."
  npm install --save-exact rea-agents@latest
  if [ -d "node_modules/rea-agents/skills/reverse-engineer-anything" ]; then
    rm -rf ".agents/skills/reverse-engineer-anything"
    cp -R "node_modules/rea-agents/skills/reverse-engineer-anything" ".agents/skills/"
  fi
fi

echo "[3/4] Ensuring tools/apktool submodule is initialized..."
if [ ! -f "tools/apktool/build.gradle.kts" ]; then
  git submodule update --init --depth 1 tools/apktool
fi

if [ "$DO_DOWNLOAD_APKTOOL" = true ]; then
  echo "[apktool] Downloading official apktool_${APKTOOL_VERSION}.jar..."
  mkdir -p "tools/apktool-bin"
  TMP_JAR="$(mktemp "tools/apktool-bin/apktool_${APKTOOL_VERSION}.jar.XXXXXX")"
  if curl -fsSL "$APKTOOL_URL" -o "$TMP_JAR"; then
    if command -v sha256sum >/dev/null 2>&1; then
      ACTUAL_SHA="$(sha256sum "$TMP_JAR" | awk '{print $1}')"
    else
      ACTUAL_SHA="$(shasum -a 256 "$TMP_JAR" | awk '{print $1}')"
    fi
    if [ "$ACTUAL_SHA" != "$APKTOOL_SHA256" ]; then
      rm -f "$TMP_JAR"
      echo "SHA-256 mismatch for apktool_${APKTOOL_VERSION}.jar! Expected $APKTOOL_SHA256, got $ACTUAL_SHA" >&2
      exit 1
    fi
    mv "$TMP_JAR" "tools/apktool-bin/apktool_${APKTOOL_VERSION}.jar"
    ln -sf "apktool_${APKTOOL_VERSION}.jar" "tools/apktool-bin/apktool.jar"
    echo "[apktool] Verified and saved to tools/apktool-bin/apktool.jar"
  else
    rm -f "$TMP_JAR"
    echo "Failed to download $APKTOOL_URL (check outbound access to GitHub release CDN objects.githubusercontent.com)." >&2
    exit 1
  fi
fi

if [ "$DO_BUILD_APKTOOL" = true ]; then
  echo "[apktool] Building from source in tools/apktool..."
  if ! command -v javac >/dev/null 2>&1; then
    echo "Error: JDK 17+ (javac) is required to build Apktool from source." >&2
    exit 1
  fi
  (cd "tools/apktool" && ./gradlew build shadowJar proguard)
  BUILT_JAR="$(ls -1 tools/apktool/brut.apktool/apktool-cli/build/libs/apktool_*.jar 2>/dev/null | grep -vE '(-sources|-javadoc|-all)\.jar$' | sort -V | tail -n 1 || true)"
  if [ -z "$BUILT_JAR" ]; then
    echo "Error: built apktool JAR not found in build/libs." >&2
    exit 1
  fi
  mkdir -p "tools/apktool-bin"
  cp "$BUILT_JAR" "tools/apktool-bin/"
  ln -sf "$(basename "$BUILT_JAR")" "tools/apktool-bin/apktool.jar"
  echo "[apktool] Built and linked to tools/apktool-bin/apktool.jar"
fi

if [ "$DO_WITH_REA_SOURCE" = true ]; then
  echo "[optional] Cloning morluto/rea source into tools/rea-src (gitignored)..."
  if [ ! -d "tools/rea-src/.git" ]; then
    git clone --depth 1 https://github.com/morluto/rea.git tools/rea-src
  else
    git -C tools/rea-src pull --ff-only
  fi
  echo "To build from tools/rea-src (requires Node >=22.23.4 / >=24.22.1 / >=26.12.1):"
  echo "  cd tools/rea-src && npm ci && npm run build:cached"
fi

echo "[4/4] Running environment verification..."
bash "$REPO_ROOT/scripts/check-env.sh"
