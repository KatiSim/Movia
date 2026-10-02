"""Live, identity-scoped stream matrices. Signed URLs and HTTP logs stay private."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse, datetime, hashlib, json, os, re, signal, sqlite3, subprocess, sys, threading, time
import urllib.request, urllib.parse, urllib.error

parser = argparse.ArgumentParser()
parser.add_argument('--out', required=True)
parser.add_argument('--catalog', required=True)
parser.add_argument('--manifest', required=True)
parser.add_argument('--runtime', required=True)
parser.add_argument('--limit', type=int, default=500)
parser.add_argument('--workers', type=int, choices=(1,2), default=1)
parser.add_argument('--all-pairs', action='store_true')
parser.add_argument('--public-prefix', default='real-source-matrices')
args = parser.parse_args()
OUT = Path(args.out); OUT.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,args.runtime)
sys.path.insert(0,str(OUT))
from stream_validation import sanitize_streams, bind_stream_identity
from database import filter_streams_for_content
from journal import record, REPO, BRANCH
STOP = threading.Event(); PUBLISH = threading.Event()
signal.signal(signal.SIGTERM,lambda *_:STOP.set())
signal.signal(signal.SIGINT,lambda *_:STOP.set())
RESULTS=OUT/args.public_prefix; RESULTS.mkdir(exist_ok=True)
PRIVATE=OUT/(args.public_prefix+'-private'); PRIVATE.mkdir(exist_ok=True); PRIVATE.chmod(0o700)

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def fingerprint(value):return hashlib.sha256(str(value).encode()).hexdigest()[:20]
def log(action,details,artifact=None):
    result=record(action,details,artifacts=[] if artifact is None else [(artifact,args.public_prefix+'/'+artifact.name)],push=False)
    PUBLISH.set();return result
def publish():
    while not STOP.is_set() or PUBLISH.is_set():
        if not PUBLISH.wait(20):continue
        PUBLISH.clear()
        try:
            head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
            p=subprocess.run(['git','push','origin',head+':refs/heads/'+BRANCH],cwd=REPO,capture_output=True,timeout=45)
            if p.returncode:PUBLISH.set()
            (OUT/'real-audit-sync.json').write_text(json.dumps({'published':p.returncode==0,'commit':head,'at':now()}))
        except (OSError,subprocess.TimeoutExpired):PUBLISH.set()
        STOP.wait(5)

def attrs(line):
    return {m.group(1):m.group(2).strip('"') for m in re.finditer(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)',line)}
def playlist(url,headers):
    with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=6) as response:
        text=response.read(2*1024*1024).decode(errors='replace')
    if not text.lstrip().startswith('#EXTM3U'):raise ValueError('NOT_HLS_PLAYLIST')
    lines=text.splitlines();videos=[];audios=[]
    for i,line in enumerate(lines):
        if line.startswith('#EXT-X-MEDIA:'):
            a=attrs(line)
            if a.get('TYPE')=='AUDIO' and a.get('URI'):
                audios.append({'name':a.get('NAME') or a.get('LANGUAGE') or 'Не указано','language':a.get('LANGUAGE'),
                               'role':a.get('CHARACTERISTICS',''),'group':a.get('GROUP-ID'),'url':urllib.parse.urljoin(url,a['URI'])})
        elif line.startswith('#EXT-X-STREAM-INF:'):
            a=attrs(line);uri=next((row.strip() for row in lines[i+1:] if row.strip() and not row.startswith('#')),None)
            resolution=(a.get('RESOLUTION') or '0x0').lower().split('x')
            height=int(resolution[-1]) if resolution[-1].isdecimal() else 0
            if uri:videos.append({'height':height,'group':a.get('AUDIO'),'url':urllib.parse.urljoin(url,uri)})
    if not videos:videos=[{'height':0,'group':None,'url':url}]
    pairs={}
    for video in videos:
        compatible=[audio for audio in audios if video.get('group')==audio.get('group')] if video.get('group') else []
        for audio in compatible or [None]:
            key=(video['height'],audio.get('name') if audio else None,audio.get('language') if audio else None,audio.get('role') if audio else None)
            pairs.setdefault(key,(video,audio))
    return videos,audios,list(pairs.values())

def decode(video,audio,headers):
    command=['ffmpeg','-hide_banner','-nostdin','-loglevel','error','-rw_timeout','6000000','-threads','1',
             '-probesize','500000','-analyzeduration','1500000']
    header=''.join(str(k)+': '+str(v)+'\r\n' for k,v in headers.items() if not any(c in str(k)+str(v) for c in '\r\n\0'))
    if header:command+=['-headers',header]
    command+=['-i',video['url']]
    if audio:
        command+=['-rw_timeout','6000000']
        if header:command+=['-headers',header]
        command+=['-i',audio['url'],'-map','0:v:0','-map','1:a:0']
    else:command+=['-map','0:v:0','-map','0:a:0?']
    command+=['-frames:v','3','-c:v','rawvideo','-c:a','pcm_s16le','-threads','1','-f','framemd5','pipe:1']
    start=time.monotonic()
    row={'requestedHeight':video['height'],'voice':audio.get('name') if audio else None,
         'language':audio.get('language') if audio else None,'sourceFingerprint':fingerprint((video['url'],audio.get('url') if audio else None))}
    try:
        p=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=14)
        text=p.stdout.decode(errors='replace');frames=[line for line in text.splitlines() if line and not line.startswith('#')]
        vf=[line for line in frames if line.startswith('0,')];af=[line for line in frames if line.startswith('1,')]
        dimension=re.search(r'#dimensions\s+0:\s*(\d+)x(\d+)',text)
        actual=int(dimension.group(2)) if dimension else None
        good=p.returncode==0 and len(vf)>=3 and bool(af) and (not video['height'] or video['height']==actual)
        row.update(decoded=good,actualHeight=actual,videoFrames=len(vf),audioFrames=len(af),
                   audioPcmFingerprint=fingerprint('\n'.join(af)) if af else None,
                   videoFrameFingerprint=fingerprint('\n'.join(vf)) if vf else None,
                   status='PASS' if good else 'FAIL',exitCode=p.returncode,
                   errorCategory=None if good else 'CDN_DECODE_OR_FORMAT_MISMATCH')
    except subprocess.TimeoutExpired:row.update(decoded=False,status='FAIL',errorCategory='DECODER_TIMEOUT')
    row['ms']=round((time.monotonic()-start)*1000);return row

def work(movie):
    mid=str(movie['mediaId']);path=RESULTS/(mid+'.json')
    if path.exists() and json.loads(path.read_text()).get('allPreparedPairsPassed'):return
    row={'mediaId':mid,'title':movie['title'],'year':movie.get('year'),'engine':'FFmpeg','checkedAt':now(),
         'componentDecodes':[],'actualDecoded':False,'nativeSwitchingVerified':False,'allProviderSourcesVerified':False}
    try:
        if STOP.is_set():return
        with sqlite3.connect('file:'+args.catalog+'?mode=ro',uri=True) as conn:
            conn.row_factory=sqlite3.Row;card=conn.execute('SELECT * FROM movies WHERE id=?',(int(mid),)).fetchone()
        if card is None or card['media_type']!='movie':raise ValueError('NOT_EXACT_MOVIE_CARD')
        card=dict(card)
        if card['title']!=movie['title'] or int(card['year'] or 0)!=int(movie.get('year') or 0):raise ValueError('CATALOG_IDENTITY_CHANGED')
        sources=filter_streams_for_content(sanitize_streams(json.loads(card['streams'] or '[]'),require_source=True),card)
        sources=bind_stream_identity(sources,catalog_media_id=mid,title=card['title'],original_title=card.get('original_title'),year=card['year'],media_type='movie')
        sources=[s for s in sources if str(s.get('url','')).startswith(('http://','https://')) and s.get('season') is None and s.get('episode') is None]
        row['catalogBindingValidated']=True;row['movieIdentityIndependentlyVerified']=False;row['sourceRows']=len(sources)
        private=PRIVATE/(mid+'.json');private.write_text(json.dumps(sources,ensure_ascii=False));private.chmod(0o600)
        candidates=[];seen=set()
        for source in sorted(sources,key=lambda s:0 if '.m3u8' in s['url'].lower() else 1):
            headers=dict(source.get('headers') or {})
            if source.get('user_agent'):headers['User-Agent']=source['user_agent']
            profile=(source['url'],tuple(sorted(headers.items())))
            if profile in seen:continue
            seen.add(profile)
            try:
                if '.m3u8' in source['url'].lower() or source.get('transport')=='hls':
                    videos,audios,pairs=playlist(source['url'],headers)
                else:videos,audios,pairs=([{'height':0,'group':None,'url':source['url']}],[],[({'height':0,'url':source['url']},None)])
                candidates.append((source,headers,videos,audios,pairs))
                if len({(a['name'],a['language'],a['role']) for a in audios})>=3 and len({v['height'] for v in videos if v['height']})>=2:break
            except (OSError,ValueError):continue
        if not candidates:raise ValueError('NO_ACCESSIBLE_CACHED_SOURCE')
        source,headers,videos,audios,pairs=max(candidates,key=lambda c:(len({(a['name'],a['language'],a['role']) for a in c[3]}),len({v['height'] for v in c[2]})))
        row.update(preparedSourceFingerprint=fingerprint(source['url']),preparedStreamIdFingerprint=fingerprint(source.get('stream_id')),
                   manifestVoices=[{k:a.get(k) for k in ('name','language','role','group')} for a in audios],
                   manifestQualities=sorted({v['height'] for v in videos if v['height']}),advertisedCompatiblePairs=len(pairs))
        selection=sorted(pairs,key=lambda pair:(pair[0]['height'],pair[1].get('name','') if pair[1] else ''))
        if not args.all_pairs:selection=selection[:1]
        for video,audio in selection:
            if STOP.is_set():break
            log('real_source_decode_begin',{'mediaId':mid,'quality':video['height'],'voice':audio.get('name') if audio else None})
            result=decode(video,audio,headers);row['componentDecodes'].append(result)
            row['actualDecoded']=any(r['decoded'] for r in row['componentDecodes'])
            path.write_text(json.dumps(row,ensure_ascii=False,indent=2))
            log('real_source_decode_result',{'mediaId':mid,**result},path)
        row['allPreparedPairsTested']=len(row['componentDecodes'])==len(pairs)
        row['allPreparedPairsPassed']=row['allPreparedPairsTested'] and all(r['decoded'] for r in row['componentDecodes'])
        row['distinctManifestVoices']=len({(a['name'],a['language'],a['role']) for a in audios})
        row['distinctManifestQualities']=len(row['manifestQualities'])
    except urllib.error.HTTPError as error:row['errorCategory']='HTTP_'+str(error.code)
    except Exception as error:row['errorCategory']=str(error) if isinstance(error,ValueError) else type(error).__name__
    finally:
        path.write_text(json.dumps(row,ensure_ascii=False,indent=2))
        log('real_movie_source_matrix_result',{'mediaId':mid,'decoded':row['actualDecoded'],
            'allPreparedPairsPassed':row.get('allPreparedPairsPassed',False),'errorCategory':row.get('errorCategory')},path)
        print(json.dumps({'mediaId':mid,'decoded':row['actualDecoded'],'pairs':len(row['componentDecodes']),
                          'allPreparedPairsPassed':row.get('allPreparedPairsPassed',False),'errorCategory':row.get('errorCategory')}),flush=True)

manifest=json.loads(Path(args.manifest).read_text())[:args.limit]
publisher=threading.Thread(target=publish,daemon=True);publisher.start()
log('real_source_matrix_audit_begin',{'selected':len(manifest),'workers':args.workers,'allPairsRequested':args.all_pairs,
                                    'phoneUiInteraction':False,'priorMovieResultsIncluded':False})
try:
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        pending=[]
        for movie in manifest:
            if STOP.is_set():break
            while len(pending)>=args.workers:
                done=[f for f in pending if f.done()]
                if done:
                    for f in done:f.result();pending.remove(f)
                else:time.sleep(.1)
            pending.append(executor.submit(work,movie))
        for future in pending:future.result()
finally:
    results=[json.loads(p.read_text()) for p in RESULTS.glob('*.json') if p.stem != 'summary']
    summary={'selected':len(manifest),'attempted':len(results),'decodedMovies':sum(r['actualDecoded'] for r in results),
             'fullPreparedMatricesPassed':sum(r.get('allPreparedPairsPassed',False) for r in results),
             'withAtLeastThreeVoices':sum(r.get('distinctManifestVoices',0)>=3 for r in results),
             'withAtLeastTwoQualities':sum(r.get('distinctManifestQualities',0)>=2 for r in results),
             'nativeSwitchingVerified':False,'allProviderSourcesVerified':False,'updatedAt':now()}
    p=RESULTS/'summary.json';p.write_text(json.dumps(summary,indent=2));log('real_source_matrix_audit_end',summary,p)
    STOP.set();PUBLISH.set();publisher.join(55)
    print(json.dumps(summary),flush=True)
