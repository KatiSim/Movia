"""Resumable real Media3 audit. No fixtures, locator URLs, or tokens in reports."""
from pathlib import Path
import urllib.request, urllib.error, json, time, uuid, sqlite3, random, datetime, os, signal, hashlib, collections, sys
ROOT=Path(__file__).resolve().parent
CACHE=Path.home()/'.cache/movia-architecture-20261002'
DB=Path.home()/'projects/media-parser/catalog.db'
TOKEN=(Path.home()/'.config/movia-agent/token').read_text().strip()
STOP=False
def stop(*args):
    global STOP
    STOP=True
signal.signal(signal.SIGTERM,stop); signal.signal(signal.SIGINT,stop)
DEADLINE=datetime.datetime(2026,10,7,20,17,tzinfo=datetime.timezone.utc).timestamp()
def req(path,body=None,port=8899,timeout=8):
    q=urllib.request.Request('http://127.0.0.1:'+str(port)+('/agent/v1/' if port==8899 else '/')+path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(q,timeout=timeout) as r:
            b=r.read()
            return json.loads(b) if b and b[:1] in (b'{',b'[') else {}
    except urllib.error.HTTPError as e:
        try:d=json.loads(e.read());code=d.get('code') or d.get('error') or d.get('status')
        except Exception:code='NON_JSON'
        raise RuntimeError('HTTP_'+str(e.code)+'_'+str(code)[:60])
def act(name,args=None):
    return req('action',{'action':name,'arguments':args or {},'requestId':'hour-coverage-'+uuid.uuid4().hex})
def save_progress(records,final=False):
    body={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'target':500,
        'attemptedUniqueCards':len({r['mediaId'] for r in records}),
        'cardsWithPlaybackOperation':len({r['mediaId'] for r in records if r.get('operationId')}),
        'discoveryUnconfirmedCards':len({r['mediaId'] for r in records if r['outcome'] in {'NO_NATIVE_AT_INITIAL_READ','DISCOVERY_UNCONFIRMED'}}),
        'outcomes':dict(collections.Counter(r['outcome'] for r in records)),
        'decodedNativeCards':sum(r['outcome']=='DECODED_NATIVE' for r in records),
        'decodedFallbackCards':sum(r['outcome']=='DECODED_FALLBACK' for r in records),
        'identityErrors':sum(r['outcome']=='IDENTITY_ERROR' for r in records),
        'concurrentDiscoveryAudit':True, 'finalForThisRun':final,
        'gate500Complete':len({r['mediaId'] for r in records})>=500}
    tmp=ROOT/'android_coverage_progress.tmp';tmp.write_text(json.dumps(body,indent=2));tmp.replace(ROOT/'android_coverage_progress.json')
    print(json.dumps(body),flush=True)
selection_file=ROOT/'android_coverage_selection.json'
if not selection_file.exists():
    with sqlite3.connect('file:'+str(DB)+'?mode=ro',uri=True) as c:
        c.row_factory=sqlite3.Row
        available=[]
        for row in c.execute("SELECT id,title,year,media_type,streams FROM movies WHERE media_type IN ('movie','tv') AND streams != '[]' ORDER BY id"):
            try: streams=json.loads(row['streams'] or '[]')
            except Exception:continue
            native=[s for s in streams if isinstance(s,dict) and str(s.get('stream_id') or '').startswith('provider-item:v2:') and str(s.get('catalog_media_id') or '')==str(row['id'])]
            if not native:continue
            if row['media_type']=='tv':
                pairs=sorted({(s.get('season_number'),s.get('episode_number')) for s in native
                    if isinstance(s.get('season_number'),int) and isinstance(s.get('episode_number'),int) and s['season_number']>0 and s['episode_number']>0})
                if not pairs:continue
                se=pairs[0]
            else:se=(None,None)
            available.append({'mediaId':str(row['id']),'title':row['title'],'year':row['year'],'type':row['media_type'],'season':se[0],'episode':se[1]})
        random.Random(3442).shuffle(available)
        # Include real series and homonyms, while retaining a deterministic movie majority.
        series=[r for r in available if r['type']=='tv'][:75]
        movies=[r for r in available if r['type']=='movie'][:500-len(series)]
        rows=movies+series;random.Random(3442).shuffle(rows)
    selection_file.write_text(json.dumps({'seed':3442,'rows':rows,'size':len(rows),
        'idsSha256':hashlib.sha256(json.dumps([r['mediaId'] for r in rows]).encode()).hexdigest()},ensure_ascii=False,indent=2))
selection=json.loads(selection_file.read_text())
journal=ROOT/'android_coverage_results.jsonl'
records=[]
if journal.exists():
    for line in journal.read_text().splitlines():
        try:records.append(json.loads(line))
        except ValueError:pass
done={r['mediaId'] for r in records if not r['outcome'].startswith('INTERRUPTED') and r['outcome'] not in {'NO_NATIVE_AT_INITIAL_READ','DISCOVERY_UNCONFIRMED'}}
snapshot=req('snapshot')
assert snapshot['app']['uiAttached'] is False
(ROOT/'android_coverage_start.json').write_text(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'app':snapshot['app'],'initialLibraryCounts':snapshot['library']['counts']},indent=2))
try:
    act('player.probeSurface',{'enabled':True})
    save_progress(records)
    for item in selection['rows']:
        if STOP or time.time()>=DEADLINE:break
        if item['mediaId'] in done:continue
        owned_hash=None
        result=dict(item);result['buildSha256']=os.environ.get('MOVIA_AUDIT_APK_SHA');start=time.monotonic();result['at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        try:
            act('player.stop')
            path='api/movie/'+item['mediaId']+'/stream'
            if item['season'] is not None:path+='?season='+str(item['season'])+'&episode='+str(item['episode'])
            body=req(path,port=8888,timeout=12)
            assert str(body.get('mediaId'))==item['mediaId'],'catalog identity response mismatch'
            sources=[s for s in body.get('streams',[]) if isinstance(s,dict) and
                str(s.get('stream_id') or '').startswith('provider-item:v2:') and str(s.get('catalog_media_id') or '')==item['mediaId']]
            if item['season'] is not None:
                sources=[s for s in sources if s.get('season_number')==item['season'] and s.get('episode_number')==item['episode']]
            result['initialResponseStatus']=body.get('status')
            result['initialDiscoveryStatus']=body.get('discoveryStatus')
            # A queued discovery is not provider unavailability. Poll the same scoped job.
            discovery_limit=time.monotonic()+18
            while not sources and body.get('refreshing') and not STOP and time.time()<DEADLINE and time.monotonic()<discovery_limit:
                time.sleep(1)
                body=req(path,port=8888,timeout=8)
                assert str(body.get('mediaId'))==item['mediaId'],'catalog identity response mismatch'
                sources=[s for s in body.get('streams',[]) if isinstance(s,dict) and str(s.get('stream_id') or '').startswith('provider-item:v2:') and str(s.get('catalog_media_id') or '')==item['mediaId']]
                if item['season'] is not None:sources=[s for s in sources if s.get('season_number')==item['season'] and s.get('episode_number')==item['episode']]
            result['finalResponseStatus']=body.get('status');result['finalDiscoveryStatus']=body.get('discoveryStatus')
            result['candidateCount']=len(sources)
            if not sources:
                result['outcome']='DISCOVERY_UNCONFIRMED' if body.get('refreshing') else ('NO_NATIVE_TERMINAL' if body.get('status')=='UNAVAILABLE' else 'NO_NATIVE_AT_INITIAL_READ')
            else:
                # Decoder evidence beats guesses; avoid initiating a cold P2P task where a direct native leaf exists.
                def key(s):
                    truth=s.get('sourceTruth') or {}
                    is_p2p=str(s.get('url') or '').lower().startswith('magnet:') or str(s.get('transport') or '').lower() in {'torrent','torrent_p2p','p2p','magnet'}
                    return (truth.get('verificationStatus') in {'FAILED','COOLDOWN','EXPIRED'},
                        not truth.get('decodedPlayback',False),is_p2p,
                        -(s.get('health_score') or .5),str(s.get('stream_id')))
                chosen=min(sources,key=key);sid=chosen['stream_id']
                result['requestedStreamId']=sid;result['provider']=chosen.get('provider') or chosen.get('source')
                result['transport']=chosen.get('transport');result['providerQuality']=chosen.get('quality')
                possible_hash=chosen.get('info_hash') or chosen.get('infoHash')
                if possible_hash:
                    try:
                        task=req('torrents',{'action':'get','hash':possible_hash},port=18090,timeout=3)
                        existed=bool(task.get('hash'))
                    except Exception:existed=False
                    if not existed:owned_hash=possible_hash
                before=req('streams').get('probeFrames',0)
                args={'mediaId':item['mediaId'],'streamId':sid,'quality':'Auto','voice':'Auto',
                    'persist':False,'recordHistory':False,'resume':False}
                if item['season'] is not None:args.update(season=item['season'],episode=item['episode'])
                reply=act('media.play',args);result['operationId']=reply.get('operationId')
                limit=time.monotonic()+18
                result['outcome']='PLAYBACK_TIMEOUT';poll_errors=0
                while not STOP and time.time()<DEADLINE and time.monotonic()<limit:
                    try:
                        d=req('diagnostics');s=req('streams');m=d.get('media3',{});snap=d.get('snapshot',{})
                        playing=snap.get('playback',{})
                        frames=s.get('probeFrames',0)
                        ready=m.get('isPlaying') is True and m.get('switchState')=='READY' and frames>before+3 and m.get('videoHeight',0)>0
                        if ready:
                            result.update(activeStreamId=s.get('activeStreamId'),mediaItemId=str(m.get('mediaItemId')),
                                actualHeight=m.get('videoHeight'),framesBefore=before,framesAfter=frames,
                                positionMs=m.get('currentPositionMs'),durationMs=playing.get('durationMs'),
                                audioTrackCount=len(s.get('audioTracks') or []),nativeArchitecture=d.get('legacyEngine',{}).get('architecture'),
                                referenceRuntimeLoaded=d.get('legacyEngine',{}).get('referenceRuntimeLoaded'),
                                firstFrameMs=m.get('firstFrameLatencyMs'))
                            if result['mediaItemId']!=item['mediaId']:
                                result['outcome']='IDENTITY_ERROR'
                            elif s.get('activeStreamId')!=sid:
                                result['outcome']='DECODED_FALLBACK';result['fallbackReason']=(d.get('streamSelection') or {}).get('fallbackReason')
                            else:result['outcome']='DECODED_NATIVE'
                            break
                        if m.get('switchState')=='FAILED':
                            result['outcome']='PLAYER_FAILED';result['fallbackReason']=(d.get('streamSelection') or {}).get('fallbackReason');break
                    except Exception:poll_errors+=1
                    time.sleep(.35)
                if STOP and result['outcome']=='PLAYBACK_TIMEOUT':result['outcome']='INTERRUPTED_FOR_INSTALL'
                result['pollErrors']=poll_errors
        except AssertionError:
            result['outcome']='IDENTITY_ERROR'
        except Exception as error:
            result['outcome']='HARNESS_OR_REQUEST_ERROR';result['errorType']=type(error).__name__;result['error']=str(error)[:120]
        finally:
            try:act('player.stop')
            except Exception:result['stopCleanupError']=True
            if owned_hash:
                try:
                    req('torrents',{'action':'rem','hash':owned_hash},port=18090,timeout=3)
                    result['ownedTorrentRemoved']=True
                except Exception:result['ownedTorrentCleanupError']=True
        result['seconds']=round(time.monotonic()-start,3)
        with journal.open('a') as f:f.write(json.dumps(result,ensure_ascii=False)+'\n');f.flush();os.fsync(f.fileno())
        records.append(result)
        if not result['outcome'].startswith('INTERRUPTED') and result['outcome'] not in {'NO_NATIVE_AT_INITIAL_READ','DISCOVERY_UNCONFIRMED'}:done.add(item['mediaId'])
        if len(records)%10==0:save_progress(records)
finally:
    try:act('player.stop');act('player.probeSurface',{'enabled':False})
    except Exception:pass
    save_progress(records,True)
    end=req('snapshot')
    (ROOT/'android_coverage_end.json').write_text(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'app':end['app'],'libraryCounts':end['library']['counts'],'playback':end['playback']},indent=2))
