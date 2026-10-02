#!/usr/bin/env python3
"""Exact catalog/provider identity, live HLS manifests, real FFmpeg frames; no screen use.
This is source/decoder verification, distinct from native Media3 switching results."""
from pathlib import Path
import sys,os,json,time,sqlite3,subprocess,datetime,hashlib,re,urllib.request,urllib.parse,threading,fcntl,signal
from concurrent.futures import ThreadPoolExecutor,as_completed
OUT=Path('/data/data/com.termux/files/home/.cache/movia-audit-500-20261002')
REPO=OUT/'repo';AUDIT=REPO/'docs/audits/films-500-20261002'
sys.path.insert(0,'/data/data/com.termux/files/home/projects/media-parser')
from collaps_provider import resolve_collaps,fetch_imdb_id_from_tmdb
from stream_validation import bind_stream_identity
from database import save_content
def persist_resolved_streams_to_catalog(mid,streams):
 primary=streams[0];return bool(save_content({'id':int(mid),'playback_url':primary.get('url',''),'voice':primary.get('voice',''),'quality':primary.get('quality',''),'seeders':0,'streams':streams,'link_verified':1,'replace_direct_variants':True}))
MANIFEST=json.loads((OUT/'additional-manifest.json').read_text())
END=datetime.datetime.fromisoformat('2026-10-02T11:29:00+00:00').timestamp()
STOP=threading.Event();CHANGED=threading.Event();LOCK=threading.RLock();SUMMARY={}
signal.signal(signal.SIGTERM,lambda *a:STOP.set())
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def git(*a):return subprocess.run(['git',*a],cwd=REPO,capture_output=True,text=True,timeout=30)
def summarize():
 rows=[json.loads(p.read_text()) for p in (AUDIT/'internal-results').glob('*.json')]
 variants=[v for r in rows for v in r.get('componentDecodes',[])]
 return {'checked':len(rows),'selected':500,'identityValidated':sum(r.get('identityValidated',False) for r in rows),'withHttpSource':sum(r.get('httpSources',0)>0 for r in rows),'moviesDecoded':sum(r.get('actualDecoded',False) for r in rows),'withAtLeastThreeManifestVoices':sum(r.get('distinctManifestVoices',0)>=3 for r in rows),'withAtLeastTwoManifestQualities':sum(r.get('distinctManifestQualities',0)>=2 for r in rows),'componentDecodesPassed':sum(v.get('decoded',False) for v in variants),'componentDecodesFailed':sum(not v.get('decoded',False) for v in variants),'nativeSwitchingVerified':False,'allMatrixCombinationsVerified':False,'updatedAt':now()}
def record(event,extra=None):
 with LOCK:
  with (OUT/'git-journal.lock').open('a') as lock:
   fcntl.flock(lock,fcntl.LOCK_EX)
   log=AUDIT/'actions.jsonl';seq=sum(1 for _ in log.open())+1
   with log.open('a') as f:f.write(json.dumps({'seq':seq,'at':now(),**event},ensure_ascii=False)+'\n');f.flush();os.fsync(f.fileno())
   SUMMARY.update(summarize());(AUDIT/'internal-summary.json').write_text(json.dumps(SUMMARY,ensure_ascii=False,indent=2)+'\n')
   paths=[str(log.relative_to(REPO)),'docs/audits/films-500-20261002/internal-summary.json']
   if extra:paths.append(str(extra.relative_to(REPO)))
   assert git('add','--',*paths).returncode==0
   assert git('commit','-m',f'Internal source audit {seq}: '+event['action']).returncode==0
   CHANGED.set()
def publish():
 while not STOP.is_set() or CHANGED.is_set():
  CHANGED.wait(2)
  if not CHANGED.is_set():continue
  CHANGED.clear()
  with (OUT/'git-journal.lock').open('a') as lock:
   fcntl.flock(lock,fcntl.LOCK_EX);head=git('rev-parse','HEAD').stdout.strip()
  try:
   p=subprocess.run(['git','push','origin',head+':refs/heads/movia-audit-500-20261002'],cwd=REPO,capture_output=True,text=True,timeout=45)
   if p.returncode:CHANGED.set()
   else:(OUT/'internal-sync.json').write_text(json.dumps({'commit':head,'publishedAt':now(),'error':None}))
  except subprocess.TimeoutExpired:CHANGED.set()
def alive():return not STOP.is_set() and time.time()<END and SUMMARY.get('moviesDecoded',0)<500
def attrs(s):
 return {m.group(1):m.group(2).strip('"') for m in re.finditer(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)',s)}
def get_text(url,headers):
 with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=5) as r:
  return r.read(2*1024*1024).decode(errors='replace')
def master_choices(url,headers):
 text=get_text(url,headers);lines=text.splitlines();audio=[];video=[]
 for i,line in enumerate(lines):
  if line.startswith('#EXT-X-MEDIA:'):
   a=attrs(line)
   if a.get('TYPE')=='AUDIO' and a.get('URI'):
    audio.append({'name':a.get('NAME') or a.get('LANGUAGE') or 'Unlabelled','language':a.get('LANGUAGE'),'group':a.get('GROUP-ID'),'url':urllib.parse.urljoin(url,a['URI'])})
  elif line.startswith('#EXT-X-STREAM-INF:'):
   a=attrs(line);uri=next((x for x in lines[i+1:] if x and not x.startswith('#')),None)
   if uri:
    h=int((a.get('RESOLUTION') or '0x0').split('x')[-1])
    video.append({'height':h,'group':a.get('AUDIO'),'url':urllib.parse.urljoin(url,uri)})
 if not video:video=[{'height':0,'group':None,'url':url}]
 unique={}
 for v in video:unique.setdefault(v['height'],v)
 return list(unique.values()),audio
def decode(video,audio,headers):
 cmd=['ffmpeg','-hide_banner','-nostdin','-loglevel','error','-rw_timeout','6000000','-threads','1','-probesize','300000','-analyzeduration','1000000']
 header=''.join(str(k)+': '+str(v)+'\r\n' for k,v in headers.items() if '\n' not in str(k)+str(v))
 if header:cmd+=['-headers',header]
 cmd+=['-i',video['url']]
 if audio:
  cmd+=['-rw_timeout','6000000']
  if header:cmd+=['-headers',header]
  cmd+=['-i',audio['url'],'-map','0:v:0','-map','1:a:0']
 else:cmd+=['-map','0:v:0','-map','0:a:0?']
 cmd+=['-frames:v','3','-c:v','rawvideo','-c:a','pcm_s16le','-threads','1','-f','framemd5','pipe:1']
 started=time.monotonic()
 try:
  p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=12)
  body=p.stdout.decode(errors='replace');data=[x for x in body.splitlines() if x and not x.startswith('#')]
  vf=[x for x in data if x.startswith('0,')];af=[x for x in data if x.startswith('1,')]
  match=re.search(r'#dimensions 0:\\s*(\\d+)x(\\d+)',body)
  # FFmpeg versions differ in spaces after the dimensions field.
  if not match:match=re.search(r'#dimensions\s+0:\s*(\d+)x(\d+)',body)
  actual=int(match.group(2)) if match else None
  return {'decoded':p.returncode==0 and len(vf)>=3 and (bool(af) if audio else True),'requestedHeight':video.get('height'),'actualHeight':actual,'qualityMatches':actual==video['height'] if video.get('height') else None,'voice':audio['name'] if audio else None,'voiceLanguage':audio.get('language') if audio else None,'videoFrames':len(vf),'audioFrames':len(af),'audioPcmFingerprint':hashlib.sha256('\n'.join(af).encode()).hexdigest()[:20] if af else None,'ms':round((time.monotonic()-started)*1000),'errorCategory':None if p.returncode==0 else 'DECODER_OR_CDN_FAILURE','exitCode':p.returncode}
 except subprocess.TimeoutExpired:return {'decoded':False,'requestedHeight':video.get('height'),'voice':audio.get('name') if audio else None,'ms':round((time.monotonic()-started)*1000),'errorCategory':'DECODER_TIMEOUT'}
def work(m):
 mid=m['mediaId'];row={'mediaId':mid,'title':m['title'],'year':m['year'],'engine':'FFmpeg source verification','componentDecodes':[],'actualDecoded':False,'identityValidated':False}
 try:
  if not alive():return
  db=sqlite3.connect('file:/data/data/com.termux/files/home/projects/media-parser/catalog.db?mode=ro',uri=True)
  current=db.execute('select title,year,tmdb_id,imdb_id,media_type,seasons_count,episodes_count,original_title from movies where id=?',(int(mid),)).fetchone();db.close()
  if not current or current[4]!='movie' or (current[5] or 0)>0 or (current[6] or 0)>0:row['excluded']='TV_OR_NON_MOVIE';return
  imdb=current[3] or fetch_imdb_id_from_tmdb(title=current[0],year=current[1],tmdb_id=current[2],is_tv=False)
  if not imdb:row['errorCategory']='EXACT_IMDB_UNAVAILABLE';return
  row['identityValidated']=True
  record({'action':'exact_provider_resolve','mediaId':mid,'provider':'collaps','identity':'catalog movie ID + exact IMDb'})
  raw=resolve_collaps(title=current[0],year=current[1],tmdb_id=current[2],imdb_id=imdb,media_type='movie')
  streams=bind_stream_identity(raw,catalog_media_id=mid,title=current[0],original_title=current[7],year=current[1],media_type='movie')
  row['httpSources']=len(streams);row['providerVoiceLabels']=sorted({str(s.get('voice')) for s in streams})
  if not streams:row['errorCategory']='PROVIDER_NO_HTTP_RESULT';return
  row['catalogCached']=persist_resolved_streams_to_catalog(mid,streams)
  (OUT/'private-streams').mkdir(exist_ok=True);(OUT/'private-streams'/(mid+'.json')).write_text(json.dumps(streams,ensure_ascii=False))
  source=streams[0];headers=source.get('headers') or {}
  videos,audios=master_choices(source['url'],headers)
  row['manifestQualities']=sorted({v['height'] for v in videos if v['height']})
  row['manifestVoices']=[{'name':a['name'],'language':a['language'],'group':a['group']} for a in audios]
  row['distinctManifestVoices']=len({(a['name'],a['language']) for a in audios});row['distinctManifestQualities']=len(row['manifestQualities'])
  pairs={(v['height'],a['name']) for v in videos for a in audios if not v.get('group') or v['group']==a['group']}
  row['advertisedCompatiblePairs']=len(pairs)
  # Decode one combined pair for every movie first. Exhaustive matrix is a separate pass.
  v=sorted(videos,key=lambda v:v['height'])[0];a=next((a for a in audios if not v['group'] or v['group']==a['group']),None)
  record({'action':'internal_combined_decode','mediaId':mid,'qualityHeight':v['height'],'voice':a['name'] if a else None,'engine':'FFmpeg','selection':'first compatible manifest video + audio'})
  r=decode(v,a,headers);row['componentDecodes'].append(r);row['actualDecoded']=r['decoded']
  row['allMatrixCombinationsVerified']=False
 except urllib.error.HTTPError as e:row['errorCategory']='HTTP_'+str(e.code)
 except Exception as e:row['errorCategory']=type(e).__name__
 finally:
  row['checkedAt']=now()
  dest=AUDIT/'internal-results'/(mid+'.json');dest.parent.mkdir(exist_ok=True);dest.write_text(json.dumps(row,ensure_ascii=False,indent=2)+'\n')
  record({'action':'internal_movie_result','mediaId':mid,'actualDecoded':row['actualDecoded'],'voices':row.get('distinctManifestVoices',0),'qualities':row.get('distinctManifestQualities',0),'errorCategory':row.get('errorCategory')},dest)
  print(json.dumps({'mediaId':mid,'decoded':row['actualDecoded'],'voices':row.get('distinctManifestVoices',0),'checked':SUMMARY.get('checked'),'moviesDecoded':SUMMARY.get('moviesDecoded'),'error':row.get('errorCategory')},ensure_ascii=False),flush=True)
(AUDIT/'internal-results').mkdir(exist_ok=True)
pub=threading.Thread(target=publish,daemon=True);pub.start()
record({'action':'internal_decoder_harness_correction','reason':'Removed premature audio frame limit that stopped the muxer before video output; previous zero-video observations are rechecked.'})
record({'action':'internal_additional_candidates_begin','scope':'Exact owned-provider resolution, real combined video+audio decoder per movie. Does not replace native Media3 switching tests.'})
existing={p.stem for p in (AUDIT/'internal-results').glob('*.json') if json.loads(p.read_text()).get('actualDecoded')}
with ThreadPoolExecutor(max_workers=1) as pool:
 pending=set()
 for m in MANIFEST:
  if not alive():break
  if m['mediaId'] in existing:continue
  while len(pending)>=1 and alive():
   done=[f for f in pending if f.done()]
   if done:
    for f in done:f.result();pending.remove(f)
   else:time.sleep(.1)
  if not alive():break
  pending.add(pool.submit(work,m))
 for f in pending:f.result()
record({'action':'internal_additional_candidates_end','result':summarize()})
STOP.set();CHANGED.set();pub.join(45)
print(json.dumps(summarize(),ensure_ascii=False),flush=True)
