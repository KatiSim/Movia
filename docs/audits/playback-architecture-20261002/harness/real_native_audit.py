"""One existing Movia Media3 session; real formats plus three new frames per pair."""
from pathlib import Path
import argparse,datetime,hashlib,json,os,signal,subprocess,sys,threading,time,urllib.request,urllib.error,uuid

parser=argparse.ArgumentParser()
parser.add_argument('--out',required=True)
parser.add_argument('--manifest',required=True)
parser.add_argument('--source-results',required=True)
parser.add_argument('--sources-private',required=True)
parser.add_argument('--build',type=int,required=True)
parser.add_argument('--limit',type=int,default=500)
parser.add_argument('--prefix',default='real-native-matrices')
args=parser.parse_args();OUT=Path(args.out);sys.path.insert(0,str(OUT))
from journal import record,REPO,BRANCH
TOKEN=(Path.home()/'.config/movia-agent/token').read_text().strip()
STOP=threading.Event();CHANGED=threading.Event()
signal.signal(signal.SIGTERM,lambda *_:STOP.set())
signal.signal(signal.SIGINT,lambda *_:STOP.set())
RESULTS=OUT/args.prefix;RESULTS.mkdir(exist_ok=True)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def digest(value):return hashlib.sha256(str(value).encode()).hexdigest()[:20]
def language(value):
    text=str(value or '').lower().replace('_','-')
    aliases={'rus':'ru','eng':'en','ukr':'uk','fre':'fr','fra':'fr','ger':'de','deu':'de','spa':'es','ita':'it','por':'pt','jpn':'ja','kor':'ko','chi':'zh','zho':'zh'}
    return aliases.get(text,text)
def log(action,details,artifact=None):
    value=record(action,details,artifacts=[] if artifact is None else [(artifact,args.prefix+'/'+artifact.name)],push=False)
    CHANGED.set();return value
def publish():
    while not STOP.is_set() or CHANGED.is_set():
        if not CHANGED.wait(20):continue
        CHANGED.clear()
        try:
            head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
            p=subprocess.run(['git','push','origin',head+':refs/heads/'+BRANCH],cwd=REPO,capture_output=True,timeout=45)
            if p.returncode:CHANGED.set()
        except (OSError,subprocess.TimeoutExpired):CHANGED.set()
        STOP.wait(5)
def req(path,body=None):
    data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
    request=urllib.request.Request('http://127.0.0.1:8899/agent/v1/'+path,data=data,
        headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=6) as response:return json.load(response)
    except urllib.error.HTTPError as error:raise RuntimeError('AGENT_HTTP_'+str(error.code))
def act(action,arguments=None,mid=None):
    values=dict(arguments or {});public=dict(values)
    if 'streamId' in public:public['streamIdFingerprint']=digest(public.pop('streamId'))
    result=req('action',{'action':action,'arguments':values,'requestId':'architecture-audit-'+uuid.uuid4().hex})
    okay=result.get('status') in {'completed','accepted'}
    log('native_'+action,{'mediaId':mid,'arguments':public,'accepted':okay,'errorCode':result.get('code')})
    if not okay:raise RuntimeError('ACTION_'+str(result.get('code') or 'FAILED'))
    return result
def observations():
    streams=req('streams');media=req('diagnostics').get('media3',{})
    return streams,media,{'mediaId':media.get('mediaItemId'),'state':media.get('playbackState'),
        'switchState':media.get('switchState'),'isPlaying':media.get('isPlaying'),
        'height':media.get('videoHeight'),'positionMs':media.get('currentPositionMs',0),
        'selectedAudioLabel':media.get('selectedAudioLabel'),'selectedAudioLanguage':media.get('selectedAudioLanguage'),
        'frames':streams.get('probeFrames',0),'voice':streams.get('activeVoice'),
        'streamFingerprint':digest(streams.get('activeStreamId')),
        'locatorFingerprint':digest((media.get('uriHost'),media.get('uriPath')))}
def wait(mid,baseline,height=None,audio=None,limit=12):
    start=time.monotonic();matched=None;last=None;inventory={}
    while not STOP.is_set() and time.monotonic()-start<limit:
        inventory,media,last=observations()
        okay=(str(media.get('mediaItemId'))==str(mid) and media.get('isPlaying') and media.get('switchState')=='READY'
            and (last.get('height') or 0)>0 and (height is None or last['height']==height)
            and (audio is None or (last['selectedAudioLabel']==audio.get('name') and
                 (not audio.get('language') or language(last['selectedAudioLanguage'])==language(audio['language'])))))
        if okay:
            if matched is None:matched=max(baseline,inventory.get('probeFrames',0))
            if inventory.get('probeFrames',0)>=matched+3:
                return {'decoded':True,'ms':round((time.monotonic()-start)*1000),'observed':last},inventory
        else:matched=None
        time.sleep(.12)
    return {'decoded':False,'ms':round((time.monotonic()-start)*1000),'observed':last,'errorCategory':'REQUESTED_TRACKS_AND_NEW_FRAMES_TIMEOUT'},inventory
def save(row):
    p=RESULTS/(row['mediaId']+'.json');p.write_text(json.dumps(row,ensure_ascii=False,indent=2))
    log('native_movie_matrix_progress',{'mediaId':row['mediaId'],'started':row.get('startup',{}).get('decoded'),
        'pairs':len(row['variants']),'fullPreparedMatrixPassed':row.get('fullPreparedMatrixPassed',False)},p)

before=req('snapshot');assert before['app']['versionCode']==args.build and before['app']['uiAttached'] is False
verification=json.loads((OUT/('verification'+str(args.build)+'.json')).read_text())
assert verification['status']=='BUILD_INSTALL_COLD_HEADLESS_NATIVE_PASS'
(OUT/(args.prefix+'-user-before-private.json')).write_text(json.dumps(before,ensure_ascii=False))
publisher=threading.Thread(target=publish,daemon=True);publisher.start()
manifest=json.loads(Path(args.manifest).read_text())[:args.limit]
log('native_real_matrix_audit_begin',{'selected':len(manifest),'versionCode':args.build,'phoneUiInteraction':False,
    'evidence':'requested decoded height and audio label/language, followed by three new decoded video frames; position preserved'})
last_lease=time.monotonic()
try:
    act('player.probeSurface',{'enabled':True})
    for movie in manifest:
        if STOP.is_set():break
        mid=str(movie['mediaId']);source_report=Path(args.source_results)/(mid+'.json');private=Path(args.sources_private)/(mid+'.json')
        if not source_report.exists() or not private.exists():continue
        report=json.loads(source_report.read_text())
        if not report.get('actualDecoded'):continue
        previous=RESULTS/(mid+'.json')
        if previous.exists() and json.loads(previous.read_text()).get('fullPreparedMatrixPassed'):continue
        if req('snapshot')['app']['uiAttached']:raise RuntimeError('USER_UI_ATTACHED_AUDIT_STOPPED')
        if time.monotonic()-last_lease>2400:
            # Renew only the existing headless audit service, without creating an Activity.
            subprocess.run(['rish','-c','am start-foreground-service -n app.movia.android/.agent.AgentAuditService'],
                env=dict(os.environ,RISH_APPLICATION_ID='com.termux'),stdin=subprocess.DEVNULL,capture_output=True,timeout=15,check=True)
            last_lease=time.monotonic()
        sources=json.loads(private.read_text());target=next((s for s in sources if digest(s['url'])==report.get('preparedSourceFingerprint')),None)
        if target is None:continue
        logical=[];seen=set()
        for audio in report.get('manifestVoices',[]):
            key=(audio.get('name'),audio.get('language'),audio.get('role',''))
            if key not in seen:seen.add(key);logical.append(audio)
        same=[s for s in sources if s['url']==target['url'] and s.get('headers',{})==target.get('headers',{})
              and s.get('user_agent')==target.get('user_agent')]
        row={'mediaId':mid,'title':movie['title'],'year':movie.get('year'),'build':args.build,'checkedAt':now(),
             'variants':[],'allProviderSourcesVerified':False,'studioNamesIndependentlyVerified':False}
        act('player.stop',mid=mid);baseline=req('streams').get('probeFrames',0);start=time.monotonic()
        act('media.play',{'mediaId':mid,'title':movie['title'],'quality':'Auto','voice':'Auto','streamId':target.get('stream_id'),
            'recordHistory':False,'persist':False,'resume':False},mid)
        row['startup'],inventory=wait(mid,baseline,limit=16)
        row['startup']['commandToReadyMs']=round((time.monotonic()-start)*1000)
        save(row)
        if not row['startup']['decoded']:continue
        quality=sorted({track['height'] for track in inventory.get('videoTracks',[]) if (track.get('height') or 0)>0})
        supported={(track.get('voice'),language(track.get('language'))) for track in inventory.get('audioTracks',[])}
        choices=[]
        for source in same:
            index=source.get('audio_track_index')
            if isinstance(index,int) and 0<=index<len(logical):
                audio=logical[index]
                if (audio.get('name'),language(audio.get('language'))) in supported:
                    choices.append((source['voice'],audio))
        if not choices:
            for track in inventory.get('audioTracks',[]):
                choices.append((track.get('id') or track['voice'],{'name':track['voice'],'language':track.get('language')}))
        pairs=[];keys=set()
        for height in quality:
            for voice,audio in choices:
                key=(height,voice,audio.get('name'),audio.get('language'))
                if key not in keys:keys.add(key);pairs.append((height,voice,audio))
        row['preparedSupportedPairs']=len(pairs);row['preparedQualities']=quality
        row['preparedVoices']=[{'choice':v,'actualName':a.get('name'),'language':a.get('language')} for v,a in choices]
        for height,voice,audio in pairs:
            if STOP.is_set():break
            prior,_,observed=observations();baseline=prior.get('probeFrames',0)
            variant={'quality':str(height)+'p','voice':voice,'expectedAudioLabel':audio.get('name'),'expectedLanguage':audio.get('language')}
            act('player.selectQuality',{'quality':str(height)+'p','persist':False},mid)
            act('player.selectVoice',{'voice':voice,'persist':False},mid)
            changed,_=wait(mid,baseline,height,audio)
            variant.update(changed);current=changed.get('observed') or {}
            variant['positionPreserved']=bool(changed['decoded'] and current.get('positionMs',0)>=observed.get('positionMs',0)-1200)
            variant['audioTrackIdentityChanged']=changed['decoded'] and (current.get('selectedAudioLabel'),current.get('selectedAudioLanguage'))!=(observed.get('selectedAudioLabel'),observed.get('selectedAudioLanguage'))
            variant['videoQualityChanged']=changed['decoded'] and current.get('height')!=observed.get('height')
            variant['status']='PASS' if changed['decoded'] and variant['positionPreserved'] else 'FAIL'
            row['variants'].append(variant);save(row)
        row['fullPreparedMatrixTested']=bool(pairs) and len(row['variants'])==len(pairs)
        row['fullPreparedMatrixPassed']=row['fullPreparedMatrixTested'] and all(v['status']=='PASS' for v in row['variants'])
        save(row);print(json.dumps({'mediaId':mid,'pairs':len(pairs),'passed':row['fullPreparedMatrixPassed']}),flush=True)
finally:
    try:
        act('player.stop');act('player.probeSurface',{'enabled':False})
        after=req('snapshot');assert before['library']==after['library'] and before['settings']==after['settings'],'USER_STATE_CHANGED'
        rows=[json.loads(p.read_text()) for p in RESULTS.glob('*.json') if p.stem!='summary']
        variants=[v for row in rows for v in row['variants']]
        summary={'versionCode':args.build,'selected':len(manifest),'attempted':len(rows),
            'decodedMovies':sum(row.get('startup',{}).get('decoded',False) for row in rows),
            'fullPreparedMatricesPassed':sum(row.get('fullPreparedMatrixPassed',False) for row in rows),
            'variantPairsPassed':sum(v['status']=='PASS' for v in variants),'variantPairsFailed':sum(v['status']=='FAIL' for v in variants),
            'userLibraryPreserved':True,'userSettingsPreserved':True,'allProviderSourcesVerified':False,'updatedAt':now()}
        path=RESULTS/'summary.json';path.write_text(json.dumps(summary,indent=2));log('native_real_matrix_audit_end',summary,path)
        print(json.dumps(summary),flush=True)
    finally:
        STOP.set();CHANGED.set();publisher.join(55)
