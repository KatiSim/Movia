#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BACKEND_LINK="$HOME/projects/media-parser"
TORR_VERSION="MatriX.144.1"
TORR_URL="https://github.com/YouROK/TorrServer/releases/download/${TORR_VERSION}/TorrServer-android-arm64"
TORR_SHA="bb7e9b4d0dc894f8da3e32496e7487be93b8f8b04ada549396a7ab4dc85ea63b"
TORR_BIN="$HOME/.local/lib/movia-torrserver/TorrServer-android-arm64"
SERVICE_ROOT="${PREFIX:-/data/data/com.termux/files/usr}/var/service"

for cmd in python3 curl aria2c sha256sum; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "FAIL: missing $cmd"; exit 1; }
done

mkdir -p "$HOME/projects"
if [ ! -e "$BACKEND_LINK" ]; then
  ln -s "$ROOT/backend" "$BACKEND_LINK"
elif [ "$(realpath "$BACKEND_LINK" 2>/dev/null || true)" != "$(realpath "$ROOT/backend")" ]; then
  echo "NOTE: keeping existing backend runtime at $BACKEND_LINK"
fi

mkdir -p "$HOME/.aria2" "$BACKEND_LINK/torrent_cache"
if [ ! -f "$HOME/.aria2/aria2.conf" ]; then
  sed "s#__MOVIA_TORRENT_CACHE__#$BACKEND_LINK/torrent_cache#g"     "$ROOT/agent/runtime/aria2.conf.example" > "$HOME/.aria2/aria2.conf"
  chmod 600 "$HOME/.aria2/aria2.conf"
  echo "Created $HOME/.aria2/aria2.conf"
else
  echo "Keeping existing $HOME/.aria2/aria2.conf"
fi

mkdir -p "$(dirname "$TORR_BIN")"
need_torr=1
if [ -f "$TORR_BIN" ]; then
  actual="$(sha256sum "$TORR_BIN" | awk '{print $1}')"
  [ "$actual" = "$TORR_SHA" ] && need_torr=0
fi
if [ "$need_torr" -eq 1 ]; then
  tmp="$TORR_BIN.download"
  rm -f "$tmp"
  curl -fL --retry 3 --connect-timeout 15 -o "$tmp" "$TORR_URL"
  echo "$TORR_SHA  $tmp" | sha256sum -c -
  mv "$tmp" "$TORR_BIN"
  chmod 755 "$TORR_BIN"
fi
actual="$(sha256sum "$TORR_BIN" | awk '{print $1}')"
[ "$actual" = "$TORR_SHA" ] || { echo "FAIL: TorrServer checksum mismatch"; exit 1; }
echo "TorrServer ${TORR_VERSION}: $actual"

mkdir -p "$SERVICE_ROOT"
for service in movia-media-parser movia-stream-enricher movia-stream-enricher-log movia-cache-pruner movia-torrserver; do
  src="$ROOT/agent/services/$service"
  [ -d "$src" ] || continue
  rm -rf "$SERVICE_ROOT/$service.new"
  cp -a "$src" "$SERVICE_ROOT/$service.new"
  find "$SERVICE_ROOT/$service.new" -type f -name run -exec chmod 755 {} +
  if [ -e "$SERVICE_ROOT/$service" ]; then
    rm -rf "$SERVICE_ROOT/$service.recovery-backup"
    mv "$SERVICE_ROOT/$service" "$SERVICE_ROOT/$service.recovery-backup"
  fi
  mv "$SERVICE_ROOT/$service.new" "$SERVICE_ROOT/$service"
done

echo "Runtime files installed. Configure private .env first, then run:"
echo "  sv up movia-torrserver movia-media-parser movia-stream-enricher movia-stream-enricher-log movia-cache-pruner"
