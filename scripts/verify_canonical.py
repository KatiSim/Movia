#!/usr/bin/env python3
from pathlib import Path
import json,re,sys

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
# Accept either repository root or android/ as argument.
repo = root.parent if (root / 'app' / 'build.gradle.kts').exists() else root
android = repo / 'android'
gradle = android / 'app' / 'build.gradle.kts'
errors=[]
required=[
    gradle,
    android/'app/src/main/java/app/movia/android/ui/MoviaApp.kt',
    android/'app/src/main/java/app/movia/android/ui/player/MoviaPlaybackService.kt',
    android/'app/src/main/java/app/movia/android/domain/model/MediaContent.kt',
    repo/'docs/DESIGN_SYSTEM_0.9.32.md',
    repo/'docs/INTERACTION_LOGIC_0.9.32.md',
    repo/'release/Movia-0.9.32-code302.apk',
]
for p in required:
    if not p.exists(): errors.append(f'missing: {p.relative_to(repo) if p.is_relative_to(repo) else p}')
if gradle.exists():
    text=gradle.read_text()
    checks={
      'applicationId':(r'applicationId\s*=\s*"([^"]+)"','app.movia.android'),
      'namespace':(r'namespace\s*=\s*"([^"]+)"','app.movia.android'),
      'versionName':(r'versionName\s*=\s*"([^"]+)"','0.9.32'),
      'versionCode':(r'versionCode\s*=\s*(\d+)','302'),
    }
    for label,(pat,want) in checks.items():
        m=re.search(pat,text)
        if not m or m.group(1)!=want: errors.append(f'{label} must be {want}')
# Catalog is runtime SSOT; bundling an old asset catalog is a regression.
if (android/'app/src/main/assets/catalog.db').exists():
    errors.append('legacy bundled app/src/main/assets/catalog.db must not be the current catalog SSOT')
base=repo/'CURRENT_BASELINE.json'
if base.exists():
    try:
        x=json.loads(base.read_text())
        if x.get('versionName')!='0.9.32' or x.get('versionCode')!=302: errors.append('CURRENT_BASELINE version mismatch')
    except Exception as e: errors.append(f'CURRENT_BASELINE invalid: {type(e).__name__}')
print({'root':str(repo),'valid':not errors,'errors':errors})
sys.exit(1 if errors else 0)
