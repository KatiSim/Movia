"""Reconcile prior public film evidence without any new media/network requests."""
from collections import Counter, defaultdict
from pathlib import Path
import datetime
import hashlib
import json
import re
import sys

OUT = Path.home() / '.cache/movia-architecture-20261002'
DOCS = OUT / 'repo/docs/audits/playback-architecture-20261002'
DEST = OUT / 'continuation-20261003'
reference = json.loads((DOCS / 'novel-source-evidence-summary.json').read_text())
phases = list(reference['phases'])
excluded = set(reference['excludedPreviouslyUsedIds'])
attempts = defaultdict(list)
phase_counts = Counter()
input_hashes = []
selected = set()
for phase in phases:
    folder = DOCS / phase
    assert folder.is_dir(), 'Missing evidence phase: ' + phase
    for path in sorted(folder.glob('*.json')):
        if not path.stem.isdigit():
            continue
        raw = path.read_bytes()
        entry = json.loads(raw)
        media_id = str(entry['mediaId'])
        assert media_id == path.stem, 'Evidence filename/ID mismatch'
        phase_counts[phase] += 1
        input_hashes.append(str(path.relative_to(DOCS)) + ':' + hashlib.sha256(raw).hexdigest())
        if phase == 'new-real-source-current':
            selected.add(media_id)
        if media_id not in excluded:
            attempts[media_id].append((entry.get('checkedAt', ''), phase, entry))
selected -= excluded
assert set(attempts) == selected, 'Unexpected identities outside the original new-film cohort'
successes = {media_id for media_id, rows in attempts.items() if any(entry.get('actualDecoded') is True for _, _, entry in rows)}
failed = sorted(selected - successes, key=int)
rows = []
for media_id in failed:
    ordered = sorted(attempts[media_id], key=lambda item: item[0])
    at, phase, entry = ordered[-1]
    category = entry.get('errorCategory')
    if not isinstance(category, str) or not re.fullmatch(r'[A-Z0-9_:-]{1,120}', category):
        category = 'UNCLASSIFIED_IN_RECORD'
    profiles = entry.get('profileChecks') or []
    rows.append({
        'mediaId': media_id, 'title': entry.get('title'), 'year': entry.get('year'),
        'lastEvidenceAt': at, 'lastEvidenceFile': phase + '/' + media_id + '.json',
        'recordedErrorCategory': category, 'lastRecordedSourceRows': entry.get('sourceRows'),
        'lastRecordedProfilesChecked': len(profiles) if isinstance(profiles, list) else None,
        'recordedAttempts': len(ordered),
        'evidenceFiles': [p + '/' + media_id + '.json' for _, p, _ in ordered],
    })
summary = {
    'reviewedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'evidenceType': 'REANALYSIS_OF_2026_10_02_RECORDS_NOT_A_NEW_LIVE_TEST',
    'newLiveRequests': 0, 'newMoviesTested': 0,
    'phaseRecordCounts': dict(phase_counts),
    'excludedPreviouslyUsedIds': sorted(excluded),
    'distinctNewFilmIdentities': len(selected), 'decodedAtLeastOnce': len(successes),
    'withoutSuccessfulDecode': len(failed),
    'matchesPublishedCounts': (len(selected) == reference['distinctNewFilmIdentities'] and
                              len(successes) == reference['newFilmsDecodedAtLeastOnce'] and
                              len(failed) == reference['newFilmsWithoutSuccessfulDecode']),
    'observedCategories': dict(Counter(row['recordedErrorCategory'] for row in rows)),
    'rootCausesVerified': False,
    'inputEvidenceDigest': hashlib.sha256('\n'.join(input_hashes).encode()).hexdigest(),
    'all500VoiceQualityMatricesVerified': False,
    'films': rows,
}
path = DEST / 'prior-35-failures-review.json'
path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({key: value for key, value in summary.items() if key != 'films'}, ensure_ascii=False), flush=True)
sys.path.insert(0, str(OUT))
from journal import record
print(json.dumps(record('review_prior_failed_movies_without_recounting_as_new_tests',
    {key: value for key, value in summary.items() if key != 'films'},
    artifacts=[(path, 'continuation-20261003/' + path.name),
               (Path(__file__), 'continuation-20261003/review_prior_failures.py')]), ensure_ascii=False), flush=True)
