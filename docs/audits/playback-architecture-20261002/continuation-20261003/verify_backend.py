"""Run isolated backend regression tests and publish reviewed, non-private evidence."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import time

OUT = Path.home() / '.cache/movia-architecture-20261002'
REPO = OUT / 'repo'
DEST = OUT / 'continuation-20261003'
sys.path.insert(0, str(OUT))
from journal import record

phase, pattern, *changed = sys.argv[1:]
if not re.fullmatch(r'[a-z0-9-]+', phase):
    raise ValueError('Invalid evidence phase')
if not pattern.startswith('test_') or '/' in pattern:
    raise ValueError('Tests must be selected within backend/tests')
for path in changed:
    if not path.startswith(('backend/tests/', 'backend/runtime/')) or '..' in Path(path).parts:
        raise ValueError('Only explicitly named isolated backend sources can be staged')
assert not subprocess.check_output(['git', 'diff', '--cached', '--name-only'], cwd=REPO, text=True).strip()
env = dict(os.environ, PYTHONPATH=str(REPO / 'backend/runtime'),
           MOVIA_DATA_DIR=str(DEST / (phase + '-data')), PYTHONDONTWRITEBYTECODE='1')
started = time.monotonic()
log_path = DEST / (phase + '.log')
with log_path.open('w') as log:
    process = subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', 'backend/tests', '-p', pattern, '-v'],
                             cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=480)
text = log_path.read_text()
count = re.search(r'Ran (\d+) tests', text)
failed_names = sorted(set(re.findall(r'^(?:FAIL|ERROR): (test_\w+)', text, re.M)))
failed_counts = re.search(r'FAILED \(([^)]+)\)', text)
state = {
    'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'phase': phase, 'pattern': pattern,
    'tests': int(count.group(1)) if count else None,
    'exitCode': process.returncode,
    'status': 'PASS' if process.returncode == 0 else 'FAIL',
    'failureCounts': failed_counts.group(1) if failed_counts else '',
    'failingTestMethods': failed_names,
    'durationSeconds': round(time.monotonic() - started, 3),
    'sourceHeadBeforeCheckpoint': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
    'sourceHashes': {path: hashlib.sha256((REPO / path).read_bytes()).hexdigest() for path in changed},
    'logSha256': hashlib.sha256(log_path.read_bytes()).hexdigest(),
    'isolatedSourcePaths': changed,
    'productionRuntimeModified': False, 'androidSourcesModified': False,
    'phoneUiUsed': False, 'realMoviesTested': 0,
}
json_path = DEST / (phase + '.json')
json_path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n')
# This index belongs only to the isolated worktree. Never call record(paths=...),
# which would copy from the user's separate working tree.
if changed:
    subprocess.run(['git', 'add', '--', *changed], cwd=REPO, check=True)
print(json.dumps(state, ensure_ascii=False), flush=True)
print(json.dumps(record('20261003_' + phase.replace('-', '_'), state,
    artifacts=[(json_path, 'continuation-20261003/' + json_path.name)]), ensure_ascii=False), flush=True)
sys.exit(process.returncode)
