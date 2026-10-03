"""Read-only preservation checks followed by an isolated audit checkpoint.

Does not connect to Movia, open Android UI, change the active parser, or install an APK.
"""
from pathlib import Path
import ast
import datetime
import hashlib
import json
import os
import subprocess
import sys

OUT = Path.home() / '.cache/movia-architecture-20261002'
REPO = OUT / 'repo'
ROOT = Path.home() / 'projects/movia'
ACTIVE = Path.home() / 'projects/media-parser'
DEST = OUT / 'continuation-20261003'
BRANCH = 'movia-playback-architecture-20261002'
baseline = json.loads((DEST / 'baseline.json').read_text())
env = dict(os.environ, GIT_OPTIONAL_LOCKS='0')

def git(*args, cwd=REPO):
    return subprocess.check_output(['git', *args], cwd=cwd, env=env, text=True, timeout=40).strip()

def digest(path):
    if not path.is_file():
        return None
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

source_paths = [
    'backend/runtime/stream_validation.py',
    'backend/runtime/stream_identity.py',
    'backend/tests/test_track_index_contract.py',
    'backend/tests/test_stream_identity_missing_titles.py',
    'backend/tests/test_playback_boundary_integration.py',
]
changed = git('diff', '--name-only', baseline['baseCommit'], 'HEAD').splitlines()
non_audit_changed = sorted(path for path in changed if not path.startswith('docs/audits/'))
compile_results = {}
new_test_methods = {}
for name in source_paths:
    text = (REPO / name).read_text()
    compile(text, name, 'exec')
    compile_results[name] = True
    if '/tests/' in name:
        tree = ast.parse(text)
        new_test_methods[name] = sum(isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith('test_')
                                     for node in ast.walk(tree))
root_head = git('rev-parse', 'HEAD', cwd=ROOT)
root_index = digest(ROOT / '.git/index')
root_status_hash = hashlib.sha256(git('status', '--porcelain=v1', cwd=ROOT).encode()).hexdigest()
head = git('rev-parse', 'HEAD')
remote = git('ls-remote', '--heads', 'origin', BRANCH).split()[0]
whitespace = subprocess.run(['git', 'diff', '--check', baseline['baseCommit'], 'HEAD'], cwd=REPO,
                            env=env, capture_output=True, text=True, timeout=30)
active_files = {}
for name in ('stream_validation', 'stream_identity'):
    active_hash = digest(ACTIVE / (name + '.py'))
    before_hash = digest(DEST / (name + '.before.py'))
    active_files[name + '.py'] = {
        'activeFileExists': active_hash is not None,
        'activeSha256': active_hash,
        'matchesPreFixSource': active_hash is not None and active_hash == before_hash,
        'matchesPatchedSource': active_hash is not None and active_hash == digest(REPO / 'backend/runtime' / (name + '.py')),
    }
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
apk_hash = digest(apk)
last_tests = json.loads((DEST / 'api-boundary-corrected.json').read_text())
checks = {
    'rootHeadPreserved': root_head == baseline['rootHead'],
    'rootIndexPreserved': root_index == baseline['rootIndexSha256'],
    'rootPorcelainStatusPreserved': root_status_hash == baseline['rootStatusSha256'],
    'isolatedWorktreeCleanBeforeCheckpoint': git('status', '--porcelain') == '',
    'remoteMatchesLocalBeforeCheckpoint': head == remote,
    'sourceScopeMatchesExactly': non_audit_changed == sorted(source_paths),
    'gitDiffCheckPassed': whitespace.returncode == 0,
    'apk327MatchesPriorVerifiedHash': apk_hash == 'e656b4de4ac1a0192766450c73b3c3764f823eac2541be7086e7b617a521d890',
    'allNewSourcesCompile': all(compile_results.values()),
    'isolatedBackendTestsPassed': last_tests['status'] == 'PASS' and last_tests['tests'] == 229,
}
state = {
    'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'status': 'PASS' if all(checks.values()) else 'CHECK_FAILED',
    'sourceHeadBeforeCheckpoint': head, 'baseCommit': baseline['baseCommit'],
    'checks': checks,
    'rootHead': root_head, 'rootIndexSha256': root_index, 'rootStatusSha256': root_status_hash,
    'changedNonAuditPaths': non_audit_changed,
    'sourceHashes': {path: digest(REPO / path) for path in source_paths},
    'newTestMethodCounts': new_test_methods, 'newTestsTotal': sum(new_test_methods.values()),
    'backendTests': last_tests['tests'], 'backendTestScope': 'ISOLATED_WORKTREE_NOT_ACTIVE_RUNTIME',
    'activeParserSourceFiles': active_files,
    'apkSha256': apk_hash, 'apkBytes': apk.stat().st_size if apk.is_file() else None,
    'androidRebuilt': False, 'androidInstalled': False,
    'installedVersionFreshlyChecked': False, 'lastKnownInstalledVersionFromPriorReport': 323,
    'productionParserActivated': False, 'nativePlaybackOrDownloadsTested': False,
    'phoneUiUsed': False, 'appUserDataReadOrWritten': False,
    'newRealMoviesTested': 0, 'newProvidersPorted': 0, 'externalHostingDeployed': False,
    'all500NativeVoiceQualityMatricesComplete': False,
    'knownLimitations': [
        'Previously persisted track zero cannot be distinguished from a clamped negative sentinel without original provider evidence.',
        'Preserved legacy unannotated HTTP rows are not independent proof of media identity.',
        'Original worktree status/HEAD/index equality does not constitute a full bytewise backup of all user data.',
        'Existing baseline test ResourceWarnings for unclosed SQLite connections remain.',
    ],
    'toolingFailures': [
        {'kind': 'tool_safety_check_blocked_before_execution', 'count': 3,
         'operations': ['combined_initial_read_only_snapshot', 'combined_source_inspection', 'inline_synthetic_reproducer'],
         'resolution': 'Narrow file reads and explicit isolated test files succeeded; no blocked mutations executed.'},
        {'kind': 'new_integration_test_helper_error', 'affectedTestMethods': 6,
         'cause': 'Unpacked a 3-element ReadService.response into 2 values.',
         'resolution': 'Corrected the test helper, not production API; full 229-test rerun passed.'},
        {'kind': 'unlogged_initial_direct_red_run', 'tests': 12,
         'jobId': 'job_20261003_192949_59b51ec5',
         'resolution': 'Reproduced and published as track-index-red.json; not counted as additional test coverage.'},
    ],
}
path = DEST / 'final-verification.json'
path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(state, ensure_ascii=False), flush=True)
sys.path.insert(0, str(OUT))
from journal import record
print(json.dumps(record('verify_20261003_preservation_scope_and_tooling_failures', state,
    artifacts=[(path, 'continuation-20261003/final-verification.json'),
               (Path(__file__), 'continuation-20261003/final_checks.py'),
               (DEST / 'verify_backend.py', 'continuation-20261003/verify_backend.py')]), ensure_ascii=False), flush=True)
sys.exit(0 if all(checks.values()) else 1)
