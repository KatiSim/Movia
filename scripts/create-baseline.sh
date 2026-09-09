#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APK="${MOVIA_APK:-$ROOT/release/Movia-0.9.32-code302.apk}"
DB="${MOVIA_CATALOG_DB:-$HOME/projects/media-parser/catalog.db}"
OUT="${MOVIA_BASELINE_OUT:-$ROOT/reference/CURRENT_RUNTIME_OBSERVATION.json}"
[ -f "$APK" ] || { echo "FAIL: APK missing: $APK"; exit 1; }
mkdir -p "$(dirname "$OUT")"
export ROOT APK DB OUT
python3 - <<'PY2'
import hashlib,json,os,sqlite3,subprocess
from pathlib import Path
root=Path(os.environ['ROOT']); apk=Path(os.environ['APK']); db=Path(os.environ['DB'])
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''): h.update(chunk)
    return h.hexdigest()
obj={
 'project':'Movia','package':'app.movia.android','versionName':'0.9.32','versionCode':302,
 'apk':{'path':str(apk),'sizeBytes':apk.stat().st_size,'sha256':sha(apk)},
 'gitCommit':subprocess.check_output(['git','-c',f'safe.directory={root}','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
 'catalog':{'path':str(db),'present':db.is_file()},
}
if db.is_file():
    c=sqlite3.connect(f'file:{db}?mode=ro',uri=True)
    meta=dict(c.execute('select key,value from catalog_meta'))
    obj['catalog'].update(rows=c.execute('select count(*) from movies').fetchone()[0],schemaVersion=meta.get('schema_version'),revision=meta.get('catalog_revision'),normalizationVersion=meta.get('normalization_version'),quickCheck=c.execute('pragma quick_check').fetchone()[0])
    c.close()
Path(os.environ['OUT']).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
PY2
echo "Wrote runtime observation: $OUT"
echo "Canonical recovery manifest CURRENT_BASELINE.json was not overwritten."
