from pathlib import Path
import json,hashlib,subprocess,datetime,re,xml.etree.ElementTree as ET,sys,shutil,collections
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';D=R/'docs/audits/hour-20261007-2034'
baseline=json.loads((C/'block08-dirty-baseline.json').read_text())
changed=[n for n,h in baseline.items() if not (R/n).exists() or hashlib.sha256((R/n).read_bytes()).hexdigest()!=h]
assert not changed,changed
secrets={'url','playback_url','download_url','downloadurl','headers','token','authorization','cookie','cookies','licenseurl','license_url','magnet'}
def clean(v):
 if isinstance(v,dict):return {k:('[redacted]' if k.lower() in secrets else clean(x)) for k,x in v.items()}
 if isinstance(v,list):return [clean(x) for x in v]
 if isinstance(v,str):return re.sub(r'https?://[^\s\"<>]+|magnet:\?[^\s\"<>]+','[redacted-locator]',v)
 return v
# Preserve the raw source/test code; redact locators only in runtime evidence/logs.
for name in ['hour-2034-offline-android-build-result.json','hour-2034-offline-install-cold.json','hour-2034-android-tests.json','hour-2034-backend-deploy.json','block08-dirty-baseline.json']:
 if (C/name).exists():(D/name).write_text(json.dumps(clean(json.loads((C/name).read_text())),ensure_ascii=False,indent=2))
for name in ['hour-2034-offline-android-build.txt','hour-2034-backend-tests.txt']:
 (D/name).write_text(clean((C/name).read_text()))
for name in ['hour-2034-build.py','hour-2034-install.py','hour-2034-deploy.py','hour-2034-runtime.py','hour-2034-final-proof.py','hour-2034-cohort-recheck.py','hour-2034-final-state.py','hour-2034-switch-matrix.py','hour-2034-negative-scope-proof.py','hour-2034-finalize.py']:
 if (C/name).exists():shutil.copy2(C/name,D/name)
for p in D.glob('*.json'):
 try:body=json.loads(p.read_text())
 except ValueError:continue
 if p.name=='variant-matrix-before.json':
  body['verificationCaveat']='sameLocator was computed from absent sanitized URLs and is not evidence of physical URL equality.'
 p.write_text(json.dumps(clean(body),ensure_ascii=False,indent=2))
for p in D.glob('*.jsonl'):
 rows=[clean(json.loads(x)) for x in p.read_text().splitlines() if x.strip()]
 p.write_text(''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in rows))
tests={'tests':0,'failures':0,'errors':0,'skipped':0}
for f in (R/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
 a=ET.parse(f).getroot().attrib
 for k in tests:tests[k]+=int(a.get(k,0))
assert tests['tests']==289 and tests['failures']==0 and tests['errors']==0,tests
backend=(C/'hour-2034-backend-tests.txt').read_text()
assert 'Ran 523 tests' in backend and '\nOK\n' in backend
install=json.loads((C/'hour-2034-offline-install-cold.json').read_text())
assert install['installSuccess'] and install['builtSha256']==install['installedSha256']
build=json.loads((C/'hour-2034-offline-android-build-result.json').read_text())
assert build['exitCode']==0
proof=json.loads((D/'final-installed-proof.json').read_text())
offline=json.loads((D/'offline_smoke.json').read_text())
assert proof.get('installedApkSha256')==install['installedSha256'], 'Runtime proof belongs to a different APK'
assert offline.get('installedApkSha256')==install['installedSha256'], 'Offline proof belongs to a different APK'
progress=json.loads((D/'android_coverage_progress.json').read_text())
records=[json.loads(x) for x in (D/'android_coverage_results.jsonl').read_text().splitlines() if x.strip()]
current=[x for x in records if x.get('buildSha256')==install['installedSha256']]
latest_current={x['mediaId']:x for x in current}
summary={
 'startedAt':'2026-10-07T20:33:56Z','recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
 'baseCheckpoint':3440,'baseCommit':'8e51f6fa0b5a253e0447b46da80ed89984c89ac4',
 'installedApkSha256':install['installedSha256'],'versionCode':335,'versionName':'0.0.1',
 'androidUnitTests':tests,'backendTests':523,'backendWarnings':'Pre-existing ResourceWarnings for unclosed test SQLite connections remain in the log.',
 'instrumentationCompiled':True,'instrumentationExecuted':False,
 'realInstalledFeedbackProof':{'allRequiredPassed':proof.get('allRequiredPassed'),'caseCount':len(proof.get('cases',[])),'installedApkSha256':proof.get('installedApkSha256')},
 'realOfflineSmoke':{k:offline.get(k) for k in ['passed','observedScenarioPassed','validationInvalidated','validationCaveat','requestWifiOnly','schedulingBudgetSeconds','downloadCompleted','downloadStatus','offlineSourceConfirmed','offlineSeek','testDownloadCleanup','playerCleanup']},
 'androidCoverageCumulative':progress,
 'androidCoverageCurrentInstalledBuild':{'uniqueCards':len(latest_current),'latestOutcomes':dict(collections.Counter(x['outcome'] for x in latest_current.values())),'attempts':len(current)},
 'coverageInterpretation':'Continued 500-card journal across multiple APKs; DECODED_FALLBACK means a different leaf than requested, not necessarily a legacy engine. A timeout is not proof of provider unavailability.',
 'existingCohortCompletion':json.loads((D/'existing-cohort-completion-summary.json').read_text()),
 'new1000IdCohortStartedThisHour':False,
 'unrelatedFilesPreserved':len(baseline),'unrelatedChangedFiles':changed,
 'buildIncludesUnrelatedUserChanges':True,
 'rebuildCaveat':'Twenty unchanged pre-existing UI/database/network inputs are deliberately excluded from this architecture commit. Their hashes are saved. The installed APK was built with those inputs.',
 'projectComplete':False,'physicalUiUsed':False,'warpChanged':False,
 'remaining':['Add scoped native failure feedback for cached leaves without sourceId; carry provider errors/pending through resolver/queue; finish bounded recovery diagnostics',
  'Finish 500-card Android audit and repeat affected failures on the installed build; real voice/quality/audio/subtitle/episode switches per transport',
  'Verify live gated providers and references without fixtures; add adapters only when real accessible protocols require them',
  'Extend offline/download coverage to HLS/DASH, selected torrent files, multiple tracks, resume/cancel; controlled CPU/memory/start measurements',
  'Remove replaced legacy wrappers after parity, then final project APK and complete signoff']
}
if (D/'final-state.json').exists():summary['finalState']=json.loads((D/'final-state.json').read_text())
if (D/'negative-scope-live-proof.json').exists():summary['negativeScopeLiveProof']=json.loads((D/'negative-scope-live-proof.json').read_text())
if (D/'quality-switch-matrix.json').exists():summary['qualitySwitchMatrix']=json.loads((D/'quality-switch-matrix.json').read_text())
(D/'hour-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
(D/'android-unit-test-counts.json').write_text(json.dumps(tests,indent=2))
# A compact status reconciles historical implementation with checks still missing.
statuses=[
(1,'Карта существует; обновлена общими пробелами','ARCHITECTURE_RU.md и прежние provider/contract audits'),
(2,'Частично','Registry/VariantTree работают; legacy-compatible balancer и wrappers ещё остаются'),
(3,'Реализовано, проверено в текущем объёме','Измерение и reload не меняют logical ID; scope-regрессии URL/headers/tracks/S/E'),
(4,'Реализовано ранее; аудит продолжен','Scoped late callbacks и очереди; четыре незавершённых результата S/T доведены до terminal'),
(5,'Частично','Content probes и decoder proof есть; статус provider error теряется в общем resolver'),
(6,'Реализован общий выбор по доказательствам','Ранжирование предыдущего часа + фактическое сохранение новых измерений'),
(7,'Частично','Reload/resume есть; общий бюджет recovery зависит от размера inventory, полный segment recovery ещё не закрыт'),
(8,'Исправлена общая публикация измерений','Первый кадр, cached feedback, точный профиль, HLS dimensions без выдуманной duration'),
(9,'Частично','Точные S/E проверяются; специальные выпуски и все переходы не завершены'),
(10,'Частично','Реальные дорожки отделены от provider voice; полная проверка subtitles/direct-track mapping остаётся'),
(11,'Частично','Нет выдуманных torrent озвучек; расширенная live audio metadata matrix остаётся'),
(12,'Частично','Native torrent decode/seek/audio и test-owned cleanup проверялись; вся матрица файлов/отмены не закрыта'),
(13,'Нативная реализация есть, активный перенос неполон','Zona gate выключен; старые wrappers ещё требуют удаления по зависимостям'),
(14,'Не закрыт','Нужен свежий живой playback; старые пустые source refs не считаются успехом'),
(15,'Adapter/ProviderContract есть','Collaps gate не включён без живого успеха'),
(16,'Не закрыт','Live голоса/качество/series/позиция и отсутствие двойного discovery не подтверждены полностью'),
(17,'Есть прежнее исследование, не закрыт','Filmix search contract/Octopus decoder были заблокированы; актуальная native live проверка остаётся'),
(18,'Не закрыт','Kinoplay прежний live handshake без refs не выполнялся; Alloha/VideoCDN references требуют проверки'),
(19,'Частично, общий дефект исправлен','Измеренное качество переживает rediscovery; полная живая матрица переключений и reopen/resume остаётся'),
(20,'Базовый сценарий пройден на текущей APK' if offline.get('passed') else 'На текущей APK не подтверждён','Результат и фактическое состояние очереди в offline_smoke.json; adaptive/multi-track/resume/cancel/torrent-file остаются'),
(21,'Частично','Ограниченные очереди существуют; общий <=5s не достигнут, нужны изолированные ресурсы/latency checks'),
(22,'Не закрыт','Удалять совместимые wrappers после доказанного функционального соответствия'),
(23,'Пройден для этого checkpoint','523 backend, 289 Android unit, APK сборка; instrumentation только скомпилирован'),
(24,'Свежие S/T IDs и baseline уже сохранены','Новой тысячи в этом часе не создавалось; это продолжение прежних выборок'),
(25,'Инвентаризация/enrichment закончены, разбор неполон','S/T имеют terminal результаты; ошибки discovery ещё нельзя полностью отделить от внешней недоступности'),
(26,'Вторая независимая инвентаризация есть','T 1000 независимых IDs; это не 1000 decoder запусков'),
(27,'В работе','Общий Android 500 journal сохраняется с SHA каждой APK'),
(28,'В работе','Точные серии повторно проверяются после исправления alias ошибки harness'),
(29,'Не завершён','500-card gate false; нужны переключения для каждого активного транспорта'),
(30,'Не закрыт','Есть playback timeouts/ошибки и общие resolver/recovery пробелы'),
(31,'Пройден для APK этого часа, не финал проекта','Install без очистки, SHA/cold native launch/crash check; весь проект ещё не готов'),
(32,'Checkpoint часа, не завершение проекта','Документы, доказательства и rollback сохраняются; projectComplete=false')
]
(D/'plan-32-status.json').write_text(json.dumps([{'block':n,'status':s,'evidenceOrRemaining':e} for n,s,e in statuses],ensure_ascii=False,indent=2))
status='''# Movia: продолжение часа 7 октября 2026, с 20:33:56 UTC

Общие причины исправлены в архитектуре. Cached native leaf больше не теряет первый кадр из-за отсутствия sourceId или поздней metadata. Подтверждённое качество сохраняется при повторной публикации того же варианта. Обновлённые URL/headers/tracks не наследуют чужие размеры, успешность или ошибки; сообщения об ошибке дополнительно защищены от interleaved discovery в SQLite. HLS размеры не требуют выдуманной общей duration. Полный контракт и оставшиеся общие причины — ARCHITECTURE_RU.md.

'''
status+=f"Установленная APK: {install['installedSha256']}, версия 0.0.1 /335, без очистки данных. Проверены {tests['tests']} Android unit и 523 backend-теста. Instrumentation Kotlin скомпилирован, но instrumentation не запускался. Результат живой проверки новой APK: {proof.get('allRequiredPassed')}; offline smoke: {offline.get('passed')}. Исходные неудачные итерации сохранены отдельно.\n\n"
status+=f"Массовый журнал: {progress['attemptedUniqueCards']} уникальных карточек, {progress['resolvedUniqueCards']} с завершённым результатом, {progress['cardsWithPlaybackOperation']} с реальным playback запросом. Точный requested leaf декодирован у {progress['decodedNativeCards']}, другой leaf — у {progress['decodedFallbackCards']}. Таймауты, ошибки и отсутствие native источника отделены. Это накопленный журнал разных APK; текущая установленная сборка имеет отдельные счётчики в hour-summary.json. Gate 500: {progress['gate500Complete']}.\n\n"
status+='Предыдущие offline попытки текущей APK: Wi-Fi-only — ENQUEUED за 100 секунд; без ограничения Wi-Fi — RUNNING до 47%, но остановлено по бюджету 45 секунд. Это ограниченные наблюдения, не доказательство постоянной недоступности или установленной причины задержки. В попытке с бюджетом 120 секунд наблюдались download/offline/seek, но случайный параллельный тест вмешался в прогон: результат исключён из изолированной валидации; прежние результаты сохранены.\n\n'
status+='''Четыре pending результата S/T доведены до terminal. S: 469 карточек с кандидатами, 3761 leaf (3697 native); T: 417 карточек, 2839 leaf (2760 native); identity ошибок нет. Это продолжение двух независимых выборок по 1000 и сравнение с их собственными baseline, а не новая тысяча или 2000 успешных запусков. UNAVAILABLE классифицирует ответ текущего resolver и не доказывает отсутствие внутренних ошибок: найдено место потери ProviderDiscoveryOutcome.status.

Ошибка harness с season_number вместо canonical season/episode исправлена для всех серий; прежние неподтверждённые записи сохранены и повторены. Нельзя заменять фактический эпизод сезонным пакетом. Это исправление измерительного сценария, а не изменение источников приложения.

## Как сократить оставшуюся работу

1. Добавить scoped native failure feedback для cached leaf без sourceId; общие resolver ошибки/pending и независимый recovery budget разобрать вместе с массовыми playback timeouts. Повторять только затронутые сценарии после изменения.
2. Продолжать сохранённый Android 500; затем для каждого активного транспорта проверить фактическое качество, voice/audio index, позицию и точные S/E. Inventory не заменяет декодирование.
3. Отдельно закрыть актуальные live gates провайдеров; при недоступности зафиксировать конкретную причину. Новые адаптеры добавлять только под обнаруженный доступный протокол.
4. Объединить расширенные offline/download, subtitle/special-episode/resume/cancel и изолированные resource/start проверки в общую транспортную матрицу.
5. После соответствия удалить заменённые wrappers и повторить final build/install/SHA/health/crash checks, оформить полное завершение.

Повторный перенос уже реализованных registry, VariantTree, identity и scoped late publication с нуля не нужен. Исходные 32 критерия сохранены в plan-32-status.json. Проект пока не завершён; <=5 секунд для всех источников не подтверждено.

## Откат и воспроизводимость

Checkpoint до этого часа — 3440 /8e51f6fa0b5a253e0447b46da80ed89984c89ac4. APK сохранена на телефоне как ~/.cache/movia-architecture-20261002/hour-2034-rollback-3440.apk, SHA 6efd511cd1bbc513dbaff2cf8d17cd6a6b16cce58302bd95e411549b49b7554b. Промежуточная APK с уже пройденным feedback/offline — hour-2034-feedback-first-pass.apk, SHA 30242dbac563ce4a1ac26e86aa655ce18fb23b03f3c4d0114b2e56da64c5fb84.

Устанавливать rollback через pm install -r -d без очистки данных и сверять SHA. Live backend backup — hour-2034-backend-backup; вернуть сохранённые runtime файлы и перезапустить movia-media-parser/movia-stream-enricher. Новая SQLite-колонка request_profile_hash additive, старый backend её игнорирует. Не удалять базу и пользовательские downloads.

20 прежних несвязанных UI/database/network файлов не изменены и исключены из архитектурного commit; их hashes записаны. Точная повторная сборка APK требует этих прежних inputs. WARP и пользовательские настройки не сбрасывались.
'''
(D/'STATUS_RU.md').write_text(status)
# No test runs or player operations here: final checks are captured beforehand.
subprocess.run(['git','diff','--check'],cwd=R,check=True,capture_output=True)
source_paths=[
'app/src/main/java/app/movia/android/domain/playback/DomainPlaybackResolver.kt',
'app/src/main/java/app/movia/android/domain/playback/MeasuredSourceEvidence.kt',
'app/src/main/java/app/movia/android/domain/playback/NativeVariantFeedback.kt',
'app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt',
'app/src/test/java/app/movia/android/domain/playback/MeasuredSourceEvidenceTest.kt',
'app/src/test/java/app/movia/android/domain/playback/NativeVariantFeedbackTest.kt',
'backend/runtime/catalog_stream_service.py','backend/runtime/media_content_probe.py',
'backend/runtime/native_variant_feedback.py','backend/runtime/playback_availability_index.py',
'backend/runtime/source_playback_evidence.py','backend/runtime/streamer.py',
'backend/tests/test_media_content_probe.py','backend/tests/test_native_variant_feedback.py',
'backend/tests/test_stream_discovery_architecture.py']
artifacts=[str(p.relative_to(R)) for p in D.rglob('*') if p.is_file() and '__pycache__' not in str(p) and p.suffix in {'.json','.jsonl','.txt','.py','.md'}]
subprocess.run(['git','add','--',*source_paths,*artifacts],cwd=R,check=True,capture_output=True)
staged=subprocess.check_output(['git','diff','--cached','--name-only'],cwd=R,text=True).splitlines()
assert not set(staged)&set(baseline),set(staged)&set(baseline)
sys.path.insert(0,str(C));import journal
result=journal.record('scope_decoder_measurements_and_failures_to_native_variant_request_profile',
 details={'projectComplete':False,'installedApkSha256':install['installedSha256'],'androidUnitTests':tests,
 'backendTests':523,'liveFeedbackPassed':proof.get('allRequiredPassed'),'realOfflinePassed':offline.get('passed'),
 'androidCoverage':progress,'unrelatedFilesPreserved':len(baseline),'sourcePaths':source_paths},paths=(),push=True)
(C/'hour-2034-checkpoint.json').write_text(json.dumps(result,indent=2))
remote=subprocess.check_output(['git','ls-remote','origin','refs/heads/movia-playback-architecture-20261002'],cwd=R,text=True,timeout=45).strip()
assert remote.split()[0]==result['commit'],remote
print(json.dumps(dict(result,remoteShaVerified=True,summary=summary),ensure_ascii=False),flush=True)
