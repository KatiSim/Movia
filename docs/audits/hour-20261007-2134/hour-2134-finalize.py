from pathlib import Path
import hashlib,json,subprocess,sys,re,shutil,datetime,collections,xml.etree.ElementTree as ET,zipfile
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';D=R/'docs/audits/hour-20261007-2134'
baseline=json.loads((C/'block08-dirty-baseline.json').read_text())
changed=[n for n,h in baseline.items() if not (R/n).exists() or hashlib.sha256((R/n).read_bytes()).hexdigest()!=h]
assert not changed,changed
def clean(v):
    secrets={'url','playback_url','download_url','downloadurl','headers','token','authorization','cookie','cookies','licenseurl','license_url','magnet'}
    if isinstance(v,dict):return {k:('[redacted]' if k.lower() in secrets else clean(x)) for k,x in v.items()}
    if isinstance(v,list):return [clean(x) for x in v]
    if isinstance(v,str):return re.sub(r'https?://[^\s\"<>]+|magnet:\?[^\s\"<>]+','[redacted-locator]',v)
    return v
for p in C.glob('hour-2134*.py'):shutil.copy2(p,D/p.name)
for p in C.glob('hour-2134*.json'):
    (D/p.name).write_text(json.dumps(clean(json.loads(p.read_text())),ensure_ascii=False,indent=2))
for name in ['hour-2134-android-build.txt','hour-2134-backend-tests.txt','hour-2134-discovery-tests.txt','hour-2134-feedback-tests.txt','hour-2134-before-frame-order-android-build.txt']:
    if (C/name).exists():(D/name).write_text(clean((C/name).read_text()))
for p in D.glob('*.json'):
    try:value=json.loads(p.read_text())
    except ValueError:continue
    p.write_text(json.dumps(clean(value),ensure_ascii=False,indent=2))
for p in D.glob('*.jsonl'):
    lines=[clean(json.loads(x)) for x in p.read_text().splitlines() if x.strip()]
    p.write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in lines))
tests={k:0 for k in ['tests','failures','errors','skipped']}
for f in (R/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    a=ET.parse(f).getroot().attrib
    for k in tests:tests[k]+=int(a.get(k,0))
assert tests['tests']==301 and tests['failures']==0 and tests['errors']==0,tests
backend=(C/'hour-2134-backend-tests.txt').read_text()
assert 'Ran 561 tests' in backend and '\nOK\n' in backend
build=json.loads((C/'hour-2134-android-build-result.json').read_text());assert build['exitCode']==0
install=json.loads((C/'hour-2134-install-cold.json').read_text())
assert install['installSuccess'] and install['builtSha256']==install['installedSha256']
assert hashlib.sha256((R/'app/build/outputs/apk/debug/app-debug.apk').read_bytes()).hexdigest()==install['installedSha256']
final=json.loads((D/'final-state.json').read_text());assert final['passed']
proof=json.loads((D/'final-installed-proof.json').read_text())
offline=json.loads((D/'offline_smoke.json').read_text())
assert proof['installedApkSha256']==install['installedSha256']
assert offline['installedApkSha256']==install['installedSha256']
progress=json.loads((D/'android_coverage_progress.json').read_text())
records=[json.loads(x) for x in (D/'android_coverage_results.jsonl').read_text().splitlines() if x.strip()]
cumulativeLatest={r['mediaId']:r for r in records}
progress['unresolvedCards']=sum(r['outcome'] in {'NO_NATIVE_AT_INITIAL_READ','DISCOVERY_UNCONFIRMED','DISCOVERY_ERROR','HARNESS_OR_REQUEST_ERROR','IDENTITY_ERROR','PLAYBACK_TIMEOUT','PLAYER_FAILED'} for r in cumulativeLatest.values())
progress['discoveryUnconfirmedCards']=sum(r['outcome'] in {'NO_NATIVE_AT_INITIAL_READ','DISCOVERY_UNCONFIRMED'} for r in cumulativeLatest.values())
progress['discoveryErrorCards']=sum(r['outcome']=='DISCOVERY_ERROR' for r in cumulativeLatest.values())
(D/'android_coverage_progress.json').write_text(json.dumps(progress,indent=2))
current=[r for r in records if r.get('buildSha256')==install['installedSha256']]
latest={r['mediaId']:r for r in current}
reclassified=[r for r in current if r.get('outcome')=='DISCOVERY_ERROR']
rollback=C/'hour-2134-rollback-3441.apk'
assert hashlib.sha256(rollback.read_bytes()).hexdigest()=='ec8cec0b9ec02942600bc8509af8eb4d2a8a423285a3574c8bfaccbaae928b58'
# Keep the exact pre-existing source inputs privately on the phone; do not commit unrelated work.
inputs=C/'hour-2134-rebuild-inputs.zip'
with zipfile.ZipFile(inputs,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for n in baseline:z.write(R/n,n)
summary={'startedAt':'2026-10-07T21:34:11Z','recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'baseCheckpoint':3441,'baseCommit':'1238a6d72b4e7b364ac05688492e70fb4d798435',
    'installedApkSha256':install['installedSha256'],'versionName':'0.0.1','versionCode':335,
    'androidUnitTests':tests,'backendTests':561,'instrumentationCompiled':True,'instrumentationExecuted':False,
    'backendWarnings':'Existing ResourceWarnings remain; the suite is not claimed warning-free.',
    'nativeFeedbackProof':{'allRequiredPassed':proof.get('allRequiredPassed'),'cases':len(proof.get('cases',[]))},
    'offlineSmoke':{k:offline.get(k) for k in ['passed','downloadCompleted','offlineSourceConfirmed','offlineSeek','testDownloadCleanup','playerCleanup']},
    'androidCoverageCumulative':progress,
    'androidCoverageCurrentInstalledBuild':{'uniqueCards':len(latest),'attempts':len(current),'latestOutcomes':dict(collections.Counter(r['outcome'] for r in latest.values())),
        'cardsWithPlaybackOperations':sum(bool(r.get('operationId')) for r in latest.values()),
        'freshFailureFeedbackObserved':sum(bool(r.get('freshFailureFeedback')) for r in current),
        'freshUnindexedFailureFeedbackObserved':sum(bool(r.get('freshFailureFeedback')) and r.get('sourceIdPresentBefore') is False for r in current)},
    'newDiscoveryErrorResults':len(reclassified),
    'new1000IdCohortStartedThisHour':False,
    'previous1000IdCohorts':'S/T inventory snapshots are retained at checkpoint 3441; old negative classifications are not upgraded to verified unavailability.',
    'finalState':final,'unrelatedFilesPreserved':len(baseline),'unrelatedChangedFiles':changed,
    'rebuildInputsArchive':str(inputs),'rebuildInputsSha256':hashlib.sha256(inputs.read_bytes()).hexdigest(),
    'rebuildCaveat':'Installed APK includes twenty unchanged pre-existing UI/database/network inputs deliberately excluded from this architecture commit. Their hashes and private phone archive preserve the inputs; bit-identical rebuild is not claimed.',
    'projectComplete':False,
    'remaining':['Finish saved 500-card real decoder audit; repeat affected failures and verify the installed build.',
        'Complete each active transport voice/quality/audio/subtitle/episode/resume/download/offline matrix.',
        'Migrate SourceTruth evidence to distinct request profiles for parallel representations sharing one physical URI; current physical key stores one profile.',
        'Verify accessible live provider references/gates and replace remaining active legacy wrappers after parity.',
        'Measure controlled CPU/RAM/queues/cancellation and cold/warm startup; general <=5-second gate remains open.']}
for name,key in [('typed-discovery-live-proof.json','typedDiscoveryLiveProof'),('negative-scope-live-proof.json','negativeScopeLiveProof'),
                 ('real-failure-feedback.json','realFailureFeedback'),('quality-switch-matrix.json','qualitySwitchMatrix'),
                 ('parallel-profile-inventory.json','parallelProfileInventory'),('resource-observation.json','resourceObservation'),
                 ('active-provider-search-observation.json','activeProviderSearchObservation')]:
    if (D/name).exists():summary[key]=json.loads((D/name).read_text())
(D/'hour-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
(D/'android-unit-test-counts.json').write_text(json.dumps(tests,indent=2))
(D/'unrelated-input-hashes.json').write_text(json.dumps(baseline,indent=2))
statuses=json.loads((R/'docs/audits/hour-20261007-2034/plan-32-status.json').read_text())
updates={
5:('Частично; потеря outcome исправлена','Ошибки и незавершённые futures доходят до native catalog boundary; нижние старые transport functions и content матрица ещё требуют проверки'),
7:('Общее восстановление доработано','Recovery budget на шесть допусков, 60-second окно старта повторов; первоначальная подготовка и одноразовый startup handover отдельно, scope/timestamp guard и per-preparation frame feedback; полная segment/resume матрица остаётся'),
8:('Доработано системно','Каждая подготовка может сохранить новый decoder proof; sourceId возвращается в точный active scope, first-frame order проверен'),
19:('Частично','Source/quality identity и reset границы исправлены; live переключения и матрица остаются критерием'),
20:('Базовый сценарий текущей APK пройден' if offline.get('passed') else 'Базовый сценарий текущей APK не закрыт','Результат именно установленной APK в offline_smoke.json; adaptive/multi-track/torrent-file/resume/cancel остаются'),
21:('Частично','Discovery metadata и recovery bounded; общий CPU/RAM, inner provider queue и <=5s не закрыты'),
23:('Пройден для этого checkpoint','561 backend, 301 Android unit, APK build; instrumentation только compiled'),
27:('В работе',str(progress['attemptedUniqueCards'])+'/500 в накопленном журнале нескольких APK; текущая APK посчитана отдельно'),
28:('В работе','Повторены старые no-native classifications с typed discovery; точные S/E сохраняются'),
29:('Не завершён','500-card gate и полная фактическая матрица каждого транспорта ещё не пройдены'),
30:('Не закрыт','Общие feedback/discovery/recovery причины исправлены; реальные timeouts/errors остаются'),
31:('Пройден для APK часа, не финал проекта','Install/SHA/cold headless/crash/backend parity и пользовательские данные проверены'),
32:('Checkpoint часа, не завершение проекта','Архитектура, доказательства, статусы и rollback сохранены; projectComplete=false')}
for row in statuses:
    if row['block'] in updates:row['status'],row['evidenceOrRemaining']=updates[row['block']]
(D/'plan-32-status.json').write_text(json.dumps(statuses,ensure_ascii=False,indent=2))
status=f"""# Movia: час с 21:34:11 UTC, 7 октября 2026

Исправления относятся к общей архитектуре. Cached native leaf сообщает ошибку без sourceId. sourceId успешного запуска возвращается только в совпавшую подготовку и request scope. Native success/failure сохраняются в транзакции с защитой профиля и порядка событий. Первый кадр учитывается на каждой подготовке. Discovery outcome, pending и late errors проходят до карточки без создания synthetic sources. Автоматическое восстановление отделено от размера inventory.

Проверены 301 Android unit и 561 backend-тест. Instrumentation скомпилирован, не запускался. APK 0.0.1 /335 установлена без очистки данных; SHA {install['installedSha256']}. Финальные SHA/backend/crash/user-data проверки: {final['passed']}.

Четыре живых сценария exact native feedback/quality/resume: {proof.get('allRequiredPassed')}. Изолированный direct download/offline/seek: {offline.get('passed')}. Данные и все неудачные проверки сохранены, критерии не подменяются компиляцией.

Массовый журнал: {progress['attemptedUniqueCards']} уникальных карточек из 500, {progress['cardsWithPlaybackOperation']} с реальными playback operations. Gate 500: {progress['gate500Complete']}. Это накопленный журнал разных APK. Текущий SHA имеет {len(latest)} отдельных карточек в mass audit; его outcomes и фактический feedback записаны отдельно. DECODED_FALLBACK означает другой leaf, а не обязательно legacy engine. Timeout не доказывает недоступность.

Три live карточки с прежним no-native результатом показали реальные provider errors в новом typed contract. Причина ответа теперь отделена от отсутствия вариантов; успешный playback для этих карточек этим не доказан. Старые NO_NATIVE_TERMINAL повторяются, неудачные discovery больше не смешиваются с harness/request errors. Прежние S/T по 1000 продолжают существовать как inventory/enrichment evidence; новой тысячи в этом часе нет.

Оставшаяся работа сгруппирована: завершить 500 journal; закрыть live транспортную матрицу вариантов/дорожек/серий/offline; проверить gates и реальные references провайдеров; измерить ресурсы и старты; удалить заменённые wrappers после соответствия и выполнить финальное завершение проекта. Подробный статус всех 32 блоков — plan-32-status.json. Цель ≤5 секунд для всех источников не подтверждена.

## Откат

Checkpoint 3441 /1238a6d72b4e7b364ac05688492e70fb4d798435. APK на телефоне: ~/.cache/movia-architecture-20261002/hour-2134-rollback-3441.apk, SHA ec8cec0b9ec02942600bc8509af8eb4d2a8a423285a3574c8bfaccbaae928b58. Установить через pm install -r -d, сохранить данные и сверить SHA.

Live backend backup: ~/.cache/movia-architecture-20261002/hour-2134-backend-backup. Восстановить сохранённые runtime файлы и перезапустить movia-media-parser/movia-stream-enricher. Новый discovery_outcome может остаться неиспользуемым при старом streamer/catalog service; удалять базы и downloads не требуется.

20 несвязанных inputs не изменены и не включены в архитектурный commit. Их частный архив на телефоне — hour-2134-rebuild-inputs.zip, SHA {summary['rebuildInputsSha256']}. Для повторной сборки потребуются прежние inputs; идентичность байтов новой сборки не обещается. WARP не изменён; physical UI не использован.

"""
status += '\nПереключения текущей APK: 240→480 и Auto прошли, 480→240 осталось без кадров за 35 секунд с BUFFERING_TIMEOUT. Requested quality/voice и позиция сохранялись; active voice отсутствовал вместе с active source, подмена озвучки этим не доказана. Полную матрицу переключений не засчитываем.\n\nПроверка sourceId: первоначальный запуск с требованием сохранения того же active option при последующем polling сохранён отдельно. Причина изменения этого последующего наблюдения не установлена. Повторная проверка учитывает sourceId на декодированном кадре либо его привязку к точному active scope; успешная свежая запись backend проверяется отдельно. Это исправление критерия измерения, не дополнительное изменение приложения.\n\nSourceTruth пока хранит один request profile на физический URI. Полная параллельная матрица нескольких дорожек/представлений одного URI требует отдельной миграции доказательств по профилям и повторной проверки.\n'
(D/'STATUS_RU.md').write_text(status)
subprocess.run(['git','diff','--check'],cwd=R,check=True,capture_output=True)
sources=[
'app/src/main/java/app/movia/android/domain/playback/NativeVariantFeedback.kt',
'app/src/main/java/app/movia/android/domain/playback/PlaybackRecoveryBudget.kt',
'app/src/main/java/app/movia/android/domain/playback/DecoderFeedbackGate.kt',
'app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt',
'app/src/test/java/app/movia/android/domain/playback/NativeVariantFeedbackTest.kt',
'app/src/test/java/app/movia/android/domain/playback/PlaybackRecoveryBudgetTest.kt',
'app/src/test/java/app/movia/android/domain/playback/DecoderFeedbackGateTest.kt',
'backend/runtime/catalog_stream_service.py','backend/runtime/discovery_queue.py','backend/runtime/discovery_outcome.py',
'backend/runtime/native_variant_feedback.py','backend/runtime/playback_availability_index.py',
'backend/runtime/provider_discovery.py','backend/runtime/streamer.py',
'backend/tests/test_native_variant_feedback.py','backend/tests/test_on_demand_provider_registry.py',
'backend/tests/test_nested_provider_publication.py','backend/tests/test_stream_discovery_architecture.py',
'backend/tests/test_discovery_outcome.py']
artifacts=[str(p.relative_to(R)) for p in D.rglob('*') if p.is_file() and '__pycache__' not in str(p) and p.suffix in {'.json','.jsonl','.txt','.py','.md'}]
subprocess.run(['git','add','--',*sources,*artifacts],cwd=R,check=True,capture_output=True)
staged=subprocess.check_output(['git','diff','--cached','--name-only'],cwd=R,text=True).splitlines()
assert not set(staged)&set(baseline)
sys.path.insert(0,str(C));import journal
result=journal.record('carry_scoped_native_failures_and_typed_discovery_through_bounded_recovery',
    details={'installedApkSha256':install['installedSha256'],'androidUnitTests':tests,'backendTests':561,
        'liveFeedbackPassed':proof.get('allRequiredPassed'),'offlinePassed':offline.get('passed'),
        'androidCoverage':progress,'unrelatedFilesPreserved':len(baseline),'projectComplete':False},paths=(),push=True)
(C/'hour-2134-checkpoint.json').write_text(json.dumps(result,indent=2))
remote=subprocess.check_output(['git','ls-remote','origin','refs/heads/movia-playback-architecture-20261002'],cwd=R,text=True,timeout=45).strip()
assert remote.split()[0]==result['commit']
print(json.dumps(dict(result,remoteShaVerified=True,summary=summary),ensure_ascii=False),flush=True)
