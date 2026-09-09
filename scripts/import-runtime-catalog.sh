#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
IN="${1:-}"
DEST="${MOVIA_CATALOG_DB:-$HOME/projects/media-parser/catalog.db}"
[ -n "$IN" ] && [ -f "$IN" ] || { echo "Usage: scripts/import-runtime-catalog.sh SNAPSHOT.sqlite.gz"; exit 2; }
tmp="$DEST.restore.tmp"
mkdir -p "$(dirname "$DEST")"
rm -f "$tmp"
gzip -dc "$IN" > "$tmp"
python3 - "$tmp" <<'PY2'
import sqlite3, sys
p=sys.argv[1]
c=sqlite3.connect(p)
result=c.execute('pragma quick_check').fetchone()[0]
print('quick_check=', result)
print('movies=', c.execute('select count(*) from movies').fetchone()[0])
c.close()
if result != 'ok': raise SystemExit(1)
PY2
if command -v sv >/dev/null 2>&1; then sv down movia-media-parser movia-stream-enricher 2>/dev/null || true; fi
stamp="$(date +%Y%m%d_%H%M%S)"
[ ! -f "$DEST" ] || mv "$DEST" "$DEST.before-restore-$stamp"
rm -f "$DEST-wal" "$DEST-shm"
mv "$tmp" "$DEST"
if command -v sv >/dev/null 2>&1; then sv up movia-media-parser movia-stream-enricher 2>/dev/null || true; fi
echo "Restored: $DEST"
