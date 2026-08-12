#!/usr/bin/env bash
# Per-boot reconciliation for PigeonPod. Runs on every environment start and must be
# idempotent: it only (re)creates ephemeral state that does not survive a reboot.
set -euo pipefail

echo "[start] Ensuring runtime directories"
# /data persists via the environment snapshot, but /tmp is cleared on every boot and the
# app's storage temp-dir lives there.
sudo mkdir -p /data/audio /data/video /data/cover /data/ssl /data/logs /data/tools/yt-dlp 2>/dev/null || \
  mkdir -p /data/audio /data/video /data/cover /data/ssl /data/logs /data/tools/yt-dlp 2>/dev/null || true
mkdir -p /tmp/pigeon-pod
echo "[start] Ready"
