#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SRC="${MOVIA_CATALOG_DB:-$HOME/projects/media-parser/catalog.db}"
OUT="${1:-$HOME/Movia-catalog-$(date +%Y%m%d_%H%M%S).sqlite.gz}"
[ -f "$SRC" ] || { echo "FAIL: catalog DB not found: $SRC"; exit 1; }
mkdir -p "$(dirname "$OUT")"
tmp="${OUT%.gz}.tmp.sqlite"
rm -f "$tmp" "$OUT"
python3 - "$SRC" "$tmp" <<'PY2'
import sqlite3, sys
src, dst = sys.argv[1:3]
a=sqlite3.connect(f'file:{src}?mode=ro', uri=True)
b=sqlite3.connect(dst)
with b:
    a.backup(b)
print('quick_check=', b.execute('pragma quick_check').fetchone()[0])
print('movies=', b.execute('select count(*) from movies').fetchone()[0])
b.close(); a.close()
PY2
gzip -9 "$tmp"
mv "$tmp.gz" "$OUT"
sha256sum "$OUT"
echo "Exported SQLite-consistent catalog snapshot: $OUT"
