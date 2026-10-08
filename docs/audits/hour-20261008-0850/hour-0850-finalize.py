from pathlib import Path
import hashlib,json,subprocess,sys,re,shutil,datetime,collections,xml.etree.ElementTree as ET,zipfile
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';D=R/'docs/audits/hour-20261008-0850'
baseline=json.loads((C/'block08-dirty-baseline.json').read_text())
changed=[n for n,h in baseline.items() if not (R/n).exists() or hashlib.sha256((R/n).read_bytes()).hexdigest()!=h]
assert not changed,changed
def clean(v):
    secrets={'url','playback_url','download_url','downloadurl','headers','token','authorization','cookie','cookies','licenseurl','license_url','magnet','uri'}
    if isinstance(v,dict):return {k:('[redacted]' if k.lower() in secrets else clean(x)) for k,x in v.items()}
    if isinstance(v,list):return [clean(x) for x in v]
    if isinstance(v,str):return re.sub(r'https?://[^\s\"<>]+|magnet:\?[^\s\"<>]+','[redacted-locator]',v)
    return v
for p in C.glob('hour-0850*.py'):shutil.copy2(p,D/p.name)
for p in C.glob('hour-0850*.json'):
    (D/p.name).write_text(json.dumps(clean(json.loads(p.read_text())),ensure_ascii=False,indent=2))
for name in ['hour-0850-android-build.txt','hour-0850-backend-tests.txt','hour-0850-before-recovery-race-android-build.txt']:
    if (C/name).exists():(D/name).write_text(clean((C/name).read_text()))
for p in D.glob('*.json'):
    p.write_text(json.dumps(clean(json.loads(p.read_text())),ensure_ascii=False,indent=2))
for p in D.glob('*.jsonl'):
    p.write_text(''.join(json.dumps(clean(json.loads(x)),ensure_ascii=False)+'\n' for x in p.read_text().splitlines() if x.strip()))
tests={k:0 for k in ['tests','failures','errors','skipped']}
for f in (R/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    a=ET.parse(f).getroot().attrib
    for k in tests:tests[k]+=int(a.get(k,0))
assert tests=={'tests':319,'failures':0,'errors':0,'skipped':0},tests
backend=(C/'hour-0850-backend-tests.txt').read_text()
assert 'Ran 561 tests' in backend and '\nOK\n' in backend
assert json.loads((C/'hour-0850-android-build-result.json').read_text())['exitCode']==0
install=json.loads((C/'hour-0850-install-cold.json').read_text())
assert install['installSuccess'] and install['builtSha256']==install['installedSha256']
assert hashlib.sha256((R/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()==install['installedSha256']
final=json.loads((D/'final-state.json').read_text());assert final['passed']
proof=json.loads((D/'final-installed-proof.json').read_text())
offline=json.loads((D/'offline_smoke.json').read_text())
matrix=json.loads((D/'quality-switch-matrix.json').read_text())
for x in [proof,offline,matrix]:assert x['installedApkSha256']==install['installedSha256']
progress=json.loads((D/'android_coverage_progress.json').read_text())
records=[json.loads(x) for x in (D/'android_coverage_results.jsonl').read_text().splitlines() if x.strip()]
latest={r['mediaId']:r for r in records}
progress['unresolvedCards']=sum(r['outcome'] in {'NO_NATIVE_AT_INITIAL_READ','DISCOVERY_UNCONFIRMED','DISCOVERY_ERROR','HARNESS_OR_REQUEST_ERROR','IDENTITY_ERROR','PLAYBACK_TIMEOUT','PLAYER_FAILED'} for r in latest.values())
progress['discoveryErrorCards']=sum(r['outcome']=='DISCOVERY_ERROR' for r in latest.values())
(D/'android_coverage_progress.json').write_text(json.dumps(progress,indent=2))
current=[r for r in records if r.get('buildSha256')==install['installedSha256']]
currentLatest={r['mediaId']:r for r in current}
rollback=C/'hour-0850-rollback-3442.apk'
assert hashlib.sha256(rollback.read_bytes()).hexdigest()=='7592ab9e479a6dcd8f15a7f6eea8f521ef6f118b6c9c1febfe0aae9ddad23ddb'
inputs=C/'hour-0850-rebuild-inputs.zip'
with zipfile.ZipFile(inputs,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for n in baseline:z.write(R/n,n)
summary={'startedAt':'2026-10-08T06:50:53Z','recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'baseCheckpoint':3442,'baseCommit':'b192f5664ff758a1f5f2c1cc6e9b90f94d6542b8',
 'installedApkSha256':install['installedSha256'],'versionName':'0.0.1','versionCode':335,
 'androidUnitTests':tests,'backendTests':561,'instrumentationCompiled':True,'instrumentationExecuted':False,
 'backendWarnings':'Existing ResourceWarnings remain. No backend code deployed this hour.',
 'nativeFeedbackProof':{'allRequiredPassed':proof.get('allRequiredPassed'),'cases':len(proof.get('cases',[]))},
 'qualitySwitchMatrix':matrix,'offlineSmoke':{k:offline.get(k) for k in ['passed','downloadCompleted','offlineSourceConfirmed','offlineSeek','testDownloadCleanup','playerCleanup']},
 'androidCoverageCumulative':progress,'androidCoverageCurrentInstalledBuild':{'uniqueCards':len(currentLatest),'attempts':len(current),
 'latestOutcomes':dict(collections.Counter(r['outcome'] for r in currentLatest.values())),
 'cardsWithPlaybackOperations':sum(bool(r.get('operationId')) for r in currentLatest.values()),'freshFailureFeedbackObserved':sum(bool(r.get('freshFailureFeedback')) for r in current),'decodedWithoutReferenceRuntime':sum(r['outcome'].startswith('DECODED') and r.get('referenceRuntimeLoaded') is False for r in currentLatest.values())},
 'playbackObservationBudgetSeconds':45,'new1000IdCohortStartedThisHour':False,'latencyLimitations':'First frame and READY measure different events. They do not establish sustained playback or audio readiness; per-source startupLatencyMs and request-to-playing timing are separate.',
 'finalState':final,'unrelatedFilesPreserved':len(baseline),'unrelatedChangedFiles':changed,
 'rebuildInputsArchive':str(inputs),'rebuildInputsSha256':hashlib.sha256(inputs.read_bytes()).hexdigest(),
 'rebuildCaveat':'APK includes twenty unchanged pre-existing UI/database/network inputs deliberately excluded from this commit. Private archive preserves them; bit-identical rebuild not claimed.',
 'projectComplete':False,
 'remaining':['Complete 500-title decoder audit and repeat failures affected by these changes on installed SHA.',
 'Finish each active transport voice/quality/audio/subtitle/exact-episode/resume/download/offline matrix.',
 'Migrate SourceTruth evidence to request-profile-specific rows when multiple representations share one physical URI.',
 'Verify provider references and gates; investigate exact known-article refresh when provider search fails.',
 'Remove remaining legacy wrappers after functional parity.',
 'Measure CPU/RAM/inner queues/cancellation and cold/warm startup; <=5-second gate remains open.']}
for name in ['active-provider-search-observation','baseline-reproduction','selection-after-fallback-proof','reload-reference-gap','resource-observation']:
    if (D/(name+'.json')).exists():summary[name]=json.loads((D/(name+'.json')).read_text())
(D/'hour-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
(D/'android-unit-test-counts.json').write_text(json.dumps(tests,indent=2))
(D/'unrelated-input-hashes.json').write_text(json.dumps(baseline,indent=2))
statuses=json.loads((R/'docs/audits/hour-20261007-2134/plan-32-status.json').read_text())
updates={
7:('Доработано системно','Профиль DataSource привязан к MediaSource; atomic resume; decoder-frame startup watchdog; поздний reload отменяется после кадра и защищён epoch; original failure timestamp сохраняется. Полная segment/resume матрица остаётся.'),
8:('Доработано; полная матрица остаётся','Свежий decoder proof проверен отдельно от bytes/open. LoadEvidence не является доказательством качества.'),
19:('Доработано системно','Manual quality и Auto принимают identity фактически подготовленного leaf. Результаты live matrix сохранены без засчитывания fallback как точного переключения.'),
20:('Базовый сценарий пройден' if offline.get('passed') else 'Базовый сценарий не закрыт','Результат текущей APK в offline_smoke.json; multi-track/torrent-file/resume/cancel остаются.'),
21:('Частично','LoadEvidence bounded 32; profile snapshot immutable; recovery guarded. CPU/RAM, inner provider queues и <=5s остаются.'),
23:('Пройден для checkpoint','319 Android unit, 561 backend; APK build; instrumentation compiled, not executed.'),
27:('В работе',str(progress['attemptedUniqueCards'])+'/500 накопленного журнала; APK метрики разделены.'),
28:('В работе','Новые карточки продолжаются из сохранённого состояния; прежние failure/timeouts не считаются исправленными без повторной проверки.'),
29:('Не завершён','500-card gate и transport switching matrix ещё открыты.'),
30:('Не закрыт','Общие причины исправлены, все реальные неудачные проверки сохранены.'),
31:('APK часа проверена','Install/SHA/cold headless/backend parity/user data; не финал проекта.'),
32:('Checkpoint часа','Архитектура/проверки/rollback опубликованы, projectComplete=false.')}
for row in statuses:
    if row['block'] in updates:row['status'],row['evidenceOrRemaining']=updates[row['block']]
(D/'plan-32-status.json').write_text(json.dumps(statuses,ensure_ascii=False,indent=2))
(D/'STATUS_RU.md').write_text(f"""# Movia: час с 06:50:53 UTC, 8 октября 2026

Системные изменения: MediaSource сохраняет собственный immutable request profile и offline factory, включая позднее создание HLS/DASH DataSource. Позиция передаётся атомарно при установке MediaSource. Startup watchdog требует фактического первого кадра; READY недостаточно. Decoder startup timeout классифицирован как временный. Фактический кадр отменяет pending recovery; поздний ответ защищён подготовкой и frame epoch, устаревший coroutine не очищает новый recovery job. Backend получает время исходной ошибки. Manual quality/Auto принимает ID подготовленного источника, сохраняя остальные поля запроса.

SourceLoadEvidence содержит максимум 32 безопасных события OPEN/ERROR/CLOSE, phase по suffix, bytes, elapsed и HTTP code/class. URL, headers и exception text не публикуются. OPEN/bytes не доказывают decoder playback. Сравнение URI в Player.Listener не доказывает происхождение запоздалого renderer callback; строгая event-time/media-period attribution и same-URI/audio сценарии требуют отдельной проверки.

319 Android unit и 561 backend прошли. Instrumentation compiled, не executed. APK 0.0.1 /335 установлена без очистки данных. SHA {install['installedSha256']}. Live native feedback: {proof.get('allRequiredPassed')}; exact switching matrix: {matrix.get('allRequiredPassed')}; direct download/offline/seek: {offline.get('passed')}. Все неудачные сценарии сохранены. Отдельная проверка manual Auto/существующего качества после реального fallback — selection-after-fallback-proof.json; она не доказывает переход на другое разрешение.

Накопленный журнал: {progress['attemptedUniqueCards']}/500 карточек разных APK. Gate: {progress['gate500Complete']}. Текущая APK отдельно: {len(currentLatest)} карточек. Новый observation budget 45 секунд; timeout не доказывает постоянную недоступность или исчерпание полного recovery window. Прежние ошибки остаются до повторной проверки.

На двух сериях HDRezka search вернул HTTP500. Это не доказывает недоступность article/player и не объясняет автоматически все discovery errors. В API двух карточек reloadSupported не сопровождается сохранённой article reference/reload_data; force-refresh идёт через поиск. Прямое обновление по доказанной known article reference требует отдельного изучения и сохранения reference при публикации; URL по hash не угадываем. Новую тысячу IDs в этом часе не создавали.

SourceTruth сохраняет один request profile на physical URI: migration для нескольких профилей остаётся. Также остаются 500-card audit, транспортная матрица дорожек/качества/серий/offline, gates/references, legacy wrapper parity/removal, CPU/RAM/queues/startup <=5s и финальная проверка проекта. Подробные 32 статуса — plan-32-status.json. Проект не завершён.

## Откат

Checkpoint 3442 /b192f5664ff758a1f5f2c1cc6e9b90f94d6542b8. APK: ~/.cache/movia-architecture-20261002/hour-0850-rollback-3442.apk, SHA 7592ab9e479a6dcd8f15a7f6eea8f521ef6f118b6c9c1febfe0aae9ddad23ddb. pm install -r -d сохраняет данные; проверить SHA. Backend в этом часе не изменён.

20 несвязанных inputs сохранены без изменений и не включены в commit. Архив hour-0850-rebuild-inputs.zip, SHA {summary['rebuildInputsSha256']}; он нужен для повторной сборки, идентичность байтов не обещается. WARP не изменён, physical UI не использован.
""")
sources=[
'app/src/main/java/app/movia/android/domain/playback/PlaybackDataSourceScope.kt',
'app/src/main/java/app/movia/android/domain/playback/PlaybackLoadEvidence.kt',
'app/src/main/java/app/movia/android/domain/playback/DecoderFeedbackGate.kt',
'app/src/main/java/app/movia/android/domain/playback/StreamFailurePolicy.kt',
'app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt',
'app/src/main/java/app/movia/android/ui/player/StreamSettingsSelection.kt',
'app/src/main/java/app/movia/android/agent/AgentControlRuntime.kt',
'app/src/test/java/app/movia/android/domain/playback/PlaybackDataSourceScopeTest.kt',
'app/src/test/java/app/movia/android/domain/playback/PlaybackLoadEvidenceTest.kt',
'app/src/test/java/app/movia/android/domain/playback/DecoderFeedbackGateTest.kt',
'app/src/test/java/app/movia/android/ui/player/StreamSettingsSelectionTest.kt']
subprocess.run(['git','diff','--check'],cwd=R,check=True,capture_output=True)
artifacts=[str(p.relative_to(R)) for p in D.rglob('*') if p.is_file() and '__pycache__' not in str(p) and p.suffix in {'.json','.jsonl','.txt','.py','.md'}]
subprocess.run(['git','add','--',*sources,*artifacts],cwd=R,check=True,capture_output=True)
staged=subprocess.check_output(['git','diff','--cached','--name-only'],cwd=R,text=True).splitlines()
assert not set(staged)&set(baseline)
sys.path.insert(0,str(C));import journal
result=journal.record('bind_media_sources_to_request_profiles_and_guard_decoder_recovery',details={
 'installedApkSha256':install['installedSha256'],'androidUnitTests':tests,'backendTests':561,
 'liveFeedbackPassed':proof.get('allRequiredPassed'),'qualityMatrixPassed':matrix.get('allRequiredPassed'),
 'offlinePassed':offline.get('passed'),'androidCoverage':progress,'unrelatedFilesPreserved':len(baseline),'projectComplete':False},paths=(),push=True)
(C/'hour-0850-checkpoint.json').write_text(json.dumps(result,indent=2))
remote=subprocess.check_output(['git','ls-remote','origin','refs/heads/movia-playback-architecture-20261002'],cwd=R,text=True,timeout=45).strip()
assert remote.split()[0]==result['commit'];assert result['seq']==3443,result
status=subprocess.check_output(['git','status','--porcelain=v1'],cwd=R,text=True).splitlines()
assert set(x[3:] for x in status)==set(baseline),status
print(json.dumps(dict(result,remoteShaVerified=True,currentApkCoverage=summary['androidCoverageCurrentInstalledBuild'],cumulative=progress),ensure_ascii=False),flush=True)
