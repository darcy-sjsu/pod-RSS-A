#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for PigeonPod (Spring Boot backend + Vite/React frontend).
# Safe to run repeatedly: it only installs what is missing and rebuilds deterministically.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAVEN_VERSION="3.9.11"

log() { echo "[install] $*"; }

ensure_maven() {
  # The project targets Maven 3.9+ (matches the maven:3.9.6 build image). The default
  # base image may ship an older Maven or none, so install a pinned 3.9.x when needed.
  local current=""
  if command -v mvn >/dev/null 2>&1; then
    current="$(mvn -v 2>/dev/null | sed -n '1s/Apache Maven \([0-9.]*\).*/\1/p')"
  fi
  if [ "${current%%.*}" = "3" ] && [ "$(printf '%s\n' "$current" | cut -d. -f2)" -ge 9 ] 2>/dev/null; then
    log "Maven ${current} already satisfies >= 3.9"
    return
  fi
  log "Installing Maven ${MAVEN_VERSION}"
  local tarball="/tmp/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  curl -fsSL -o "$tarball" \
    "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  sudo rm -rf "/opt/apache-maven-${MAVEN_VERSION}"
  sudo tar -xzf "$tarball" -C /opt
  sudo ln -sf "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn
  hash -r
  mvn -v | head -1
}

ensure_ytdlp() {
  # yt-dlp powers the download pipeline; the app resolves it from PATH (SYSTEM_BINARY mode).
  if command -v yt-dlp >/dev/null 2>&1; then
    log "yt-dlp already on PATH: $(yt-dlp --version 2>/dev/null)"
    return
  fi
  log "Installing yt-dlp (pip --user)"
  python3 -m pip install --user --break-system-packages -q "yt-dlp"
  sudo ln -sf "$HOME/.local/bin/yt-dlp" /usr/local/bin/yt-dlp
  hash -r
  yt-dlp --version
}

ensure_data_dirs() {
  # Absolute /data layout matches the Docker/compose deployment; media dirs are created
  # here so first-run downloads and yt-dlp runtime management have a writable home.
  log "Ensuring /data directories"
  sudo mkdir -p /data/audio /data/video /data/cover /data/ssl /data/logs /data/tools/yt-dlp
  sudo chown -R "$(id -u):$(id -g)" /data
  mkdir -p /tmp/pigeon-pod
}

install_frontend() {
  log "Installing frontend dependencies"
  (cd "$REPO_ROOT/frontend" && npm install)
}

build_backend() {
  # Cursor checks out sources with a zero (1970) mtime, which makes maven-resources-plugin
  # skip copying db/migration/*.sql and messages*.properties. Refresh mtimes first so the
  # Flyway migrations and i18n bundles reach the classpath.
  log "Refreshing backend resource mtimes (workaround for zero-mtime checkout)"
  find "$REPO_ROOT/backend/src/main/resources" -type f -exec touch {} +
  log "Warming Maven cache and validating backend build"
  (cd "$REPO_ROOT/backend" && mvn -B -DskipTests clean package)
}

ensure_maven
ensure_ytdlp
ensure_data_dirs
install_frontend
build_backend
log "Bootstrap complete"
