from pathlib import Path
import ast,datetime,hashlib,json,re,subprocess,sys,urllib.request
OUT=Path('/data/data/com.termux/files/home/.cache/movia-architecture-20261002')
REPO=OUT/'repo'
REF=Path('/data/data/com.termux/files/home/.cache/movia-implementation-20261001/lazy-decompiled/sources')
APK=REF.parent.parent/'LazyMedia-Deluxe-3.466.apk'
def git(*args):return subprocess.check_output(['git',*args],cwd=REPO,text=True).strip()
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def inspect(root,relative,patterns):
 p=root/relative;text=p.read_text();lines=text.splitlines()
 return {'path':relative,'sha256':digest(p),'lines':len(lines),
 'anchors':{pattern:[i for i,line in enumerate(lines,1) if re.search(pattern,line)][:20] for pattern in patterns},
 'decompiler_warning':('Code decompiled incorrectly' in text or 'Method not decompiled' in text)}
refs={
'com/lazycatsoftware/mediaservices/Services.java':['sServers','new vq0','HDREZKA_Article'],
'obf/gs0.java':['class gs0','public gs0'],
'obf/vq0.java':['class vq0','public Class'],
'obf/k30.java':['class k30','h30','OooO0o\\(','OooOo00'],
'obf/h30.java':['onParse','OooO00o\\(k30'],
'obf/j30.java':['class j30','getFormat','OooOOOo','internal_exo'],
'obf/pl.java':['doInBackground','onPostExecute'],
'com/lazycatsoftware/lazymediadeluxe/models/service/OooO00o.java':['parseBase','parseContent','parseTorrent','setCustomHeaders','destroy\\('],
'com/lazycatsoftware/mediaservices/content/HDREZKA_ListArticles.java':['parseSearchList','processingList','parseGlobalSearchList'],
'com/lazycatsoftware/mediaservices/content/HDREZKA_Article.java':['parseContent','onParse','getSerialTranslate','parseMoviesFiles'],
'com/lazycatsoftware/lazymediadeluxe/player/ActivityExoPlayer.java':['Oooo0OO','OoooO0O','OooO0o\\(','onPause','onDestroy'],
'obf/p.java':['MappedTrackInfo','TrackSelectionView','OoooOO0','HlsMediaSource','DefaultHttpDataSource'],
'obf/o30.java':['OooO0o0','OooOOo\\(','article_hash'],
}
owned={
'backend/runtime/provider_contract.py':['class Provider','class Variant','class Deferred','flatten_variant_tree','identity_parts','language.*ru'],
'backend/runtime/provider_discovery.py':['def _cache_completed','def discover_provider_streams','_PROVIDER_FLAGS','collected_streams.extend'],
'backend/runtime/hdrezka_provider_adapter.py':['def search','def resolve_source','key=f','measured','VariantFolder'],
'backend/runtime/hdrezka_transport.py':['def translators','def playlist_leaves','def resolve_hdrezka','advertised_quality','_TRANSLATOR'],
'backend/runtime/hdrezka_episode_identity.py':['^def '],
'backend/runtime/torrent_provider_adapter.py':['def _leaf_stream_key','def _build_provider_tree','def rewrite_torrent','audio_track_index','language='],
'backend/runtime/catalog_stream_service.py':['def _rows','def __call__','def _resolve'],
'backend/runtime/content_filler.py':['def _rewrite_torrent','discover_provider_streams','_DIRECT','_TORRENT','shared'],
'backend/runtime/media_content_probe.py':['^def ','_DIMENSION'],
'backend/runtime/streamer.py':['def resolve_on_demand_streams','def persist_resolved','def _torrserver_exact','def _torrserver_prepare','MOVIA_ENABLE_'],
'backend/runtime/zona_provider_adapter.py':['def resolve_source','resolve_zona_for_title','zona_playback_architecture'],
'backend/runtime/collaps_provider_adapter.py':['def resolve_source','VariantStream','audio_track_index'],
'backend/runtime/filmix_provider_adapter.py':['def resolve_source','VariantStream','UNSUPPORTED'],
'backend/runtime/octopus_provider_adapter.py':['def resolve_article','DECODER','iframe'],
'backend/runtime/zona_mobi_provider_adapter.py':['def resolve_source','VariantStream','quality','voice'],
'backend/runtime/zona_legacy_adapters.py':['^def _resolve_'],
'backend/runtime/zona_playback_architecture.py':['^def ','^class '],
'backend/runtime/stream_validation.py':['def bind_stream_identity','def sanitize_streams'],
'backend/runtime/stream_identity.py':['^def '],
'backend/runtime/playback_availability.py':['^def ','^class '],
'backend/runtime/provider_configuration.py':['^def '],
}
android='app/src/main/java/app/movia/android/'
for rel,patterns in {
'domain/provider/MoviaProviderRegistry.kt':['class MoviaProviderRegistry','Deferred','suspend fun discover','maximumNodes'],
'domain/provider/MoviaBackendProviderAdapter.kt':['override suspend fun','groupBy'],
'domain/legacy/LegacyPlaybackResolver.kt':['referenceRuntimeLoaded','registry'],
'domain/playback/DomainPlaybackResolver.kt':['resolveStreamsWithBackend','matchesReloadIdentity','reloadStreamCandidate','resolveByTitle'],
'domain/playback/StreamRanker.kt':['fun rank','measured','content','problem'],
'domain/playback/StreamCandidate.kt':['logicalSourceId','audioTrackIndex','reload','fileIndex'],
'domain/playback/StreamRequestProfile.kt':['headersFor','userAgent'],
'domain/playback/StreamFailurePolicy.kt':['^internal','^fun ','^class '],
'domain/playback/MediaContentIdentityPolicy.kt':['fun '],
'domain/model/EpisodeNavigation.kt':['fun '],
'domain/model/PlaybackProgress.kt':['mediaRef'],
'ui/player/PlaybackSession.kt':['fun selectVoice','fun selectVideoQuality','fun switchToStream','recoverFromFailure','applyUserTrackPreferences','fun stopAndClear','MoviaPlaybackRegistry','onTracksChanged','onPlayerError'],
'ui/player/StreamSettingsSelection.kt':['fun select','firstOrNull','isAdaptive'],
'ui/player/ProviderTrackSelection.kt':['providerTrackOverride','providerAudioOrdinals'],
'ui/player/LogicalAudioTracks.kt':['fun '],
'ui/player/PlaybackChoices.kt':['fun playbackChoices','fun qualityMenu','fun voiceMenu'],
'ui/player/PlayerScreen.kt':['fun selectSubtitleTrack','fun selectAutomaticSubtitles','buildSubtitleTrackOptions'],
'ui/MoviaApp.kt':['val saved = progressByMediaRef','progressByTitle\\[title\\]','lastProgress.takeIf','saveProgress'],
'data/library/LibraryRepository.kt':['fun saveProgress'],
'data/download/DownloadPlaybackIdentity.kt':['^fun ','identity'],
'data/download/OfflineVariantSelection.kt':['fun ','audio','quality'],
'data/download/AdaptiveOfflineDownloader.kt':['fun ','Track','override'],
'data/download/OfflineDownloadWorker.kt':['fun ','variant','quality','audio'],
}.items():owned[android+rel]=patterns
testpaths=[
'backend/tests/test_provider_contract.py','backend/tests/test_provider_discovery.py',
'backend/tests/test_hdrezka_native_transport.py','backend/tests/test_hls_native_dimensions.py',
'backend/tests/test_hdrezka_provider_adapter.py','backend/tests/test_catalog_stream_service.py',
'backend/tests/test_content_filler_provider_union.py','backend/tests/test_torrent_provider_contract_integration.py',
'app/src/test/java/app/movia/android/domain/provider/MoviaProviderRegistryTest.kt',
'app/src/test/java/app/movia/android/domain/playback/DomainPlaybackResolverTest.kt',
'app/src/test/java/app/movia/android/domain/playback/StreamContentEvidenceRankingTest.kt',
'app/src/test/java/app/movia/android/ui/player/StreamSettingsSelectionTest.kt',
'app/src/test/java/app/movia/android/ui/player/LogicalAudioTracksTest.kt',
'app/src/test/java/app/movia/android/ui/player/Media3PlaybackStabilizationTest.kt',
'app/src/test/java/app/movia/android/data/download/OfflineVariantSelectionTest.kt',
'app/src/test/java/app/movia/android/domain/model/EpisodeNavigationTest.kt']
sys.path.insert(0,str(REPO/'backend/runtime'))
from provider_contract import ProviderDefinition,ProviderArticle,ProviderRequest,VariantFolder,VariantStream,flatten_variant_tree
p=ProviderDefinition('audit:provider','Audit','owned');a=ProviderArticle(p,'item','Example',2000);r=ProviderRequest('audit-id','Example',2000)
def row(q):return flatten_variant_tree(a,VariantFolder(children=[VariantStream('https://example.test/video.m3u8',stream_key='same-representation',voice='Original',quality=q)]),r)[0]
x,y=row('Не указано'),row('720p')
checks={'same_stream_key_quality_update_changes_logical_id':x['logical_source_id']!=y['logical_source_id'],
'same_stream_key_quality_update_changes_provider_item_id':x['provider_item_id']!=y['provider_item_id'],
'unknown_language_on_original_leaf':x['language']}
missing=[f for f in owned if not (REPO/f).exists()]
runtime_sources=[inspect(REPO,f,ps) for f,ps in owned.items() if (REPO/f).exists()]
reference_sources=[inspect(REF,f,ps) for f,ps in refs.items()]
backend_py=list((REPO/'backend/runtime').glob('*.py'))
loader_implementations=[]
for f in backend_py:
 tree=ast.parse(f.read_text())
 for node in ast.walk(tree):
  if isinstance(node,ast.ClassDef) and any(isinstance(b,ast.Name) and b.id=='DeferredVariantLoader' for b in node.bases):loader_implementations.append(str(f.relative_to(REPO)))
parity={}
for f in ['provider_contract.py','provider_discovery.py','hdrezka_provider_adapter.py','hdrezka_transport.py','catalog_stream_service.py','content_filler.py','torrent_provider_adapter.py','zona_mobi_provider_adapter.py']:
 live=Path('/data/data/com.termux/files/home/projects/media-parser')/f
 parity[f]=live.exists() and digest(REPO/'backend/runtime'/f)==digest(live)
flags={}
for service in ['movia-media-parser','movia-stream-enricher']:
 run=Path('/data/data/com.termux/files/usr/var/service')/service/'run'
 flags[service]=re.findall(r'^export (MOVIA_ENABLE_[A-Z_]+)=(\d+)',run.read_text(),re.M)
dirty=subprocess.check_output(['git','status','--porcelain=v1'],cwd=REPO,text=True).rstrip('\n')
dirty_hashes={}
for line in dirty.splitlines():
 relative=line[3:];path=REPO/relative
 if path.is_file():dirty_hashes[relative]=digest(path)
with urllib.request.urlopen('http://127.0.0.1:8888/health',timeout=3) as response:health=response.status
data={'block':1,'at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'repo':str(REPO),'head_before':git('rev-parse','HEAD'),
'reference_root':str(REF),'reference_apk_sha256':digest(APK),'scope':'source audit and pure contract diagnostic; no runtime implementation',
'reference_sources':reference_sources,'movia_sources':runtime_sources,
'existing_tests':[inspect(REPO,f,['def test_','@Test','fun ']) for f in testpaths],
'missing_paths':missing,'backend_deferred_loader_implementations':loader_implementations,
'diagnostic':checks,'backend_live_file_parity':parity,'service_launch_flags':flags,'health_http':health,
'service_status':subprocess.check_output(['sv','status','/data/data/com.termux/files/usr/var/service/movia-media-parser','/data/data/com.termux/files/usr/var/service/movia-stream-enricher'],text=True).strip(),
'dirty_before':dirty,'dirty_file_hashes_before':dirty_hashes,
'limitations':['Decompiled methods marked incorrect are not treated as complete executable truth.',
'Live playback evidence from 2026-10-06 was reviewed, not rerun in this block.',
'Provider endpoint availability is not inferred from source existence.',
'Source audit does not prove semantic studio identity by listening.',
'Reference APK SHA pins the local input; no bytecode-to-Java equivalence proof is claimed.']}
(OUT/'BLOCK01_REFERENCE_MOVIA_EVIDENCE_20261007.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'reference_sources':len(reference_sources),'movia_sources':len(runtime_sources),'test_files':len(testpaths),'missing':missing,'diagnostic':checks,'active_loader_implementations':loader_implementations,'live_parity':parity,'health':health},ensure_ascii=False))
