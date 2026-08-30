#!/usr/bin/env python3
from pathlib import Path
import re
import sys
import sqlite3

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('/data/data/com.termux/files/home/projects/viora')
errors = []
gradle = root / 'app/build.gradle.kts'
catalog = root / 'app/src/main/java/app/movia/android/data/catalog/CatalogRepository.kt'
asset_db = root / 'app/src/main/assets/catalog.db'
model = root / 'app/src/main/java/app/movia/android/domain/model/MediaContent.kt'
details = root / 'app/src/main/java/app/movia/android/ui/details/DetailsScreen.kt'

for path in (gradle, catalog, model, details, asset_db):
    if not path.exists():
        errors.append(f'missing: {path}')

if not errors:
    gradle_text = gradle.read_text(encoding='utf-8', errors='replace')
    catalog_text = catalog.read_text(encoding='utf-8', errors='replace')
    model_text = model.read_text(encoding='utf-8', errors='replace')
    details_text = details.read_text(encoding='utf-8', errors='replace')

    app_id = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle_text)
    namespace = re.search(r'namespace\s*=\s*"([^"]+)"', gradle_text)
    version_code = re.search(r'versionCode\s*=\s*(\d+)', gradle_text)
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_text)

    if not app_id or app_id.group(1) != 'app.viora.android':
        errors.append('applicationId must be app.viora.android')
    if not namespace or namespace.group(1) != 'app.movia.android':
        errors.append('namespace must be app.movia.android')
    if not version_code or int(version_code.group(1)) < 176:
        errors.append('versionCode must be >= 176')
    if not version_name:
        errors.append('versionName is missing')

    try:
        conn = sqlite3.connect(str(asset_db))
        cursor = conn.cursor()
        entries = cursor.execute("SELECT count(*) FROM movies").fetchone()[0]
        posters = cursor.execute("SELECT count(*) FROM movies WHERE poster_url IS NOT NULL AND poster_url != ''").fetchone()[0]
        backdrops = cursor.execute("SELECT count(*) FROM movies WHERE backdrop_url IS NOT NULL AND backdrop_url != ''").fetchone()[0]
        cast_photos = cursor.execute("SELECT count(*) FROM movies WHERE [cast] LIKE '%photo_url%'").fetchone()[0]
        conn.close()

        if entries < 157:
            errors.append(f'catalog entries={entries}, expected at least 157')
        if posters < (entries * 0.5):
            errors.append(f'posterUrl fields={posters}, entries={entries}')
        if backdrops < (entries * 0.5):
            errors.append(f'backdropUrl fields={backdrops}, entries={entries}')
        if cast_photos < 1:
            errors.append('no actor photoUrl fields found in catalog.db')
    except Exception as e:
        errors.append(f'error inspecting catalog.db: {e}')

    if 'val cast: List<Person>' not in model_text:
        errors.append('MediaContent.cast must be List<Person>')
    if 'val photoUrl: String?' not in model_text:
        errors.append('Person.photoUrl is missing')
    if 'loadActorBitmap' not in details_text:
        errors.append('actor photo loader is missing')

result = {'root': str(root), 'valid': not errors, 'errors': errors}
if not errors:
    result['message'] = 'canonical SQLite catalog baseline preflight passed'
print(result)
sys.exit(1 if errors else 0)
