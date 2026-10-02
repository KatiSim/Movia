#!/usr/bin/env python3
"""Single Media3 session, real frames, sanitized append-only history; no display operations."""
from pathlib import Path
import json,os,time,hashlib,uuid,urllib.request,urllib.error,threading,subprocess,datetime,signal,re,sys,fcntl
OUT=Path('/data/data/com.termux/files/home/.cache/movia-audit-500-20261002')
REPO=OUT/'repo'; AUDIT=REPO/'docs/audits/films-500-20261002'
TOKEN=(Path.home()/'.config/movia-agent/token').read_text().strip()
BRANCH='movia-audit-500-20261002'
LOCK=threading.RLock(); CHANGED=threading.Event(); STOP=threading.Event()
DEADLINE=datetime.datetime.fromisoformat('2026-10-02T11:29:30+00:00').timestamp()
PAUSE=OUT/'pause'; PHASE=sys.argv[1] if len(sys.argv)>1 else 'starts'
SEQ=sum(1 for _ in (AUDIT/'actions.jsonl').open())
PUBLISHED=SEQ; PUBLISH_ERROR=None
SUMMARY={'phase':PHASE,'selected':500,'attempted':0,'decodedStarted':0,'withThreeVoiceLabels':0,'withTwoQualityLabels':0,'qualitySwitchConfirmed':0,'audioTrackSwitchConfirmed':0,'voiceLocatorOnly':0,'variantPassed':0,'variantFailed':0,'variantUnverified':0,'completed':False}
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def digest(value):return hashlib.sha256(str(value).encode()).hexdigest()[:20]
def req(path,body=None):
 data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
 request=urllib.request.Request('http://127.0.0.1:8899/agent/v1/'+path,data=data,headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(request,timeout=5) as response:return json.load(response)
 except urllib.error.HTTPError as e:raise RuntimeError('Agent HTTP '+str(e.code))
def snapshot():return req('snapshot')
def observations():
 streams=req('streams');m=req('diagnostics').get('media3',{})
 audio=[(x.get('id'),x.get('voice'),x.get('language')) for x in streams.get('audioTracks',[]) if x.get('selected')]
 return streams,m,{'mediaId':m.get('mediaItemId'),'state':m.get('playbackState'),'switchState':m.get('switchState'),
  'isPlaying':m.get('isPlaying'),'height':m.get('videoHeight',0),'positionMs':m.get('currentPositionMs',0),
  'frames':streams.get('probeFrames',0),'quality':streams.get('activeQuality'),'voice':streams.get('activeVoice'),
  'streamFingerprint':digest(streams.get('activeStreamId')),'locatorFingerprint':digest((m.get('uriHost'),m.get('uriPath'))),
  'selectedAudioLabel':m.get('selectedAudioLabel'),'selectedAudioLanguage':m.get('selectedAudioLanguage'),'selectedAudio':audio}
def git(*args):return subprocess.run(['git',*args],cwd=REPO,capture_output=True,text=True,timeout=25)
def checkpoint(event,extra=None):
 with (OUT/'git-journal.lock').open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  return checkpoint_locked(event,extra)
def checkpoint_locked(event,extra=None):
 global SEQ
 with LOCK:
  SEQ=sum(1 for _ in (AUDIT/'actions.jsonl').open())+1;event={'seq':SEQ,'at':now(),**event}
  with (AUDIT/'actions.jsonl').open('a') as f:f.write(json.dumps(event,ensure_ascii=False)+'\n');f.flush();os.fsync(f.fileno())
  SUMMARY.update(updatedAt=now(),actionsRecorded=SEQ,lastPublishedAction=PUBLISHED,githubSyncError=PUBLISH_ERROR)
  (AUDIT/'summary.json').write_text(json.dumps(SUMMARY,ensure_ascii=False,indent=2)+'\n')
  names=['docs/audits/films-500-20261002/actions.jsonl','docs/audits/films-500-20261002/summary.json']
  if extra:names.append(str(extra.relative_to(REPO)))
  p=git('add','--',*names);assert p.returncode==0,'Git add failed'
  p=git('commit','-m',f'Audit action {SEQ}: '+event.get('action','checkpoint'));assert p.returncode==0,'Git commit failed'
  CHANGED.set()
def publish():
 global PUBLISHED,PUBLISH_ERROR
 while not STOP.is_set() or CHANGED.is_set():
  CHANGED.wait(2)
  if not CHANGED.is_set():continue
  with LOCK:
   CHANGED.clear();target=git('rev-parse','HEAD').stdout.strip();seq=SEQ
  try:
   p=subprocess.run(['git','push','origin',target+':refs/heads/'+BRANCH],cwd=REPO,capture_output=True,text=True,timeout=45)
   if p.returncode==0:PUBLISHED=seq;PUBLISH_ERROR=None
   else:PUBLISH_ERROR='Git push failed, exit '+str(p.returncode);CHANGED.set();STOP.wait(3)
  except subprocess.TimeoutExpired:PUBLISH_ERROR='Git push timeout';CHANGED.set();STOP.wait(3)
  (OUT/'sync-status.json').write_text(json.dumps({'recorded':SEQ,'published':PUBLISHED,'error':PUBLISH_ERROR,'updatedAt':now()},indent=2))
def act(action,args=None,media=None):
 safe=dict(args or {})
 if 'streamId' in safe:safe['streamIdFingerprint']=digest(safe.pop('streamId'))
 started=time.monotonic()
 try:
  result=req('action',{'action':action,'arguments':args or {},'requestId':'audit500-'+uuid.uuid4().hex})
  checkpoint({'action':action,'mediaId':media,'arguments':safe,'accepted':True,'requestMs':round((time.monotonic()-started)*1000)})
  return result
 except Exception as e:
  checkpoint({'action':action,'mediaId':media,'arguments':safe,'accepted':False,'error':str(e)[:120]});raise
def can_continue():return time.time()<DEADLINE and not PAUSE.exists() and not STOP.is_set()
def wait_ready(media_id,baseline,max_seconds=14,quality=None,voice=None):
 started=time.monotonic();last=None;last_streams={}
 while time.monotonic()-started<max_seconds and can_continue():
  try:
   s,m,o=observations();last=o;last_streams=s
   ready=m.get('mediaItemId')==media_id and m.get('isPlaying') and m.get('domainPlaybackState')=='READY' and m.get('switchState')=='READY' and m.get('videoHeight',0)>0 and s.get('probeFrames',0)>=baseline+3
   if ready:
    q_ok=not quality or o['height']==int(re.search(r'\d+',quality)[0])
    v_ok=not voice or str(o.get('voice','')).casefold()==voice.casefold()
    return {'decoded':True,'qualityMatches':q_ok,'voiceReportedMatches':v_ok,'ms':round((time.monotonic()-started)*1000,2),'observed':o},s
  except urllib.error.URLError as e:
   if getattr(e,'reason',None) and ('refused' in str(e).lower() or 'reset' in str(e).lower()):raise
  except (RuntimeError,TimeoutError):pass
  time.sleep(.15)
 return {'decoded':False,'ms':round((time.monotonic()-started)*1000,2),'observed':last,'reason':'bounded frame observation timeout'},last_streams
def inventory(streams):
 pairs=set();voices=set();qualities=set();source_rows=0;automatic_voices=set();technical=set()
 def meaningful(v):
  return bool(v) and v.casefold() not in ['auto','не указано','unknown','none','delete','default'] and not re.fullmatch(r'(?:rus|fre|eng|def|ukr|und)\d+(?: · \d+)?|default(?: · \d+)?',v,re.I)
 for group in streams.get('qualities',[]):
  q=group.get('quality','')
  for row in group.get('voices',[]):
   source_rows+=1;v=str(row.get('voice') or '').strip()
   if meaningful(v):voices.add(v)
   elif v:technical.add(v)
   if re.fullmatch(r'\d{3,4}p',q):
    qualities.add(q)
    if meaningful(v):pairs.add((q,v))
   elif q.lower() in ('auto','не указано') and meaningful(v):automatic_voices.add(v)
 for x in streams.get('videoTracks',[]):
  if x.get('height',0)>0:qualities.add(str(x['height'])+'p')
 for x in streams.get('audioTracks',[]):
  v=str(x.get('voice') or '').strip()
  if meaningful(v):voices.add(re.sub(r' · \d+$','',v))
  elif v:technical.add(v)
 for q in qualities:
  for v in automatic_voices:pairs.add((q,v))
 return {'voiceLabels':sorted(voices),'technicalTrackLabels':sorted(technical),'qualityLabels':sorted(qualities,key=lambda x:int(x[:-1])),'advertisedPairs':[list(x) for x in sorted(pairs)],'sourceRows':source_rows,'inventoryComplete':False}
def read_results():
 result={}
 for f in (AUDIT/'results').glob('*.json'):
  j=json.loads(f.read_text());result[j['mediaId']]=j
 return result
def refresh_summary():
 rows=list(read_results().values())
 SUMMARY.update(attempted=len(rows),decodedStarted=sum(bool(x.get('startup',{}).get('decoded')) for x in rows),
 withThreeVoiceLabels=sum(len(x.get('inventory',{}).get('voiceLabels',[]))>=3 for x in rows),
 withTwoQualityLabels=sum(len(x.get('inventory',{}).get('qualityLabels',[]))>=2 for x in rows))
 variants=[v for x in rows for v in x.get('variants',[])]
 SUMMARY.update(variantPassed=sum(v.get('status')=='PASS' for v in variants),variantFailed=sum(v.get('status')=='FAIL' for v in variants),
 variantUnverified=sum(v.get('status')=='UNVERIFIED' for v in variants),qualitySwitchConfirmed=sum(bool(v.get('qualityActualChanged')) for v in variants),
 audioTrackSwitchConfirmed=sum(bool(v.get('audioTrackIdentityChanged')) for v in variants),voiceLocatorOnly=sum(bool(v.get('locatorChanged')) and not v.get('audioTrackIdentityChanged') for v in variants))
def save_movie(row):
 f=AUDIT/'results'/ (row['mediaId']+'.json');f.parent.mkdir(parents=True,exist_ok=True);f.write_text(json.dumps(row,ensure_ascii=False,indent=2)+'\n')
 refresh_summary();checkpoint({'action':'movie_result','mediaId':row['mediaId'],'decoded':row.get('startup',{}).get('decoded'), 'voices':len(row.get('inventory',{}).get('voiceLabels',[])),'qualities':len(row.get('inventory',{}).get('qualityLabels',[]))},f)
def interrupted(*args):STOP.set()
signal.signal(signal.SIGTERM,interrupted);signal.signal(signal.SIGINT,interrupted)
thread=threading.Thread(target=publish,daemon=True);thread.start()
manifest=json.loads((OUT/'manifest.json').read_text());before=snapshot();(OUT/('user-before-'+PHASE+'.json')).write_text(json.dumps(before,ensure_ascii=False))
try:
 assert before['app']['versionCode']==320 and before['app']['uiAttached']==False,'Unexpected runtime or UI attached'
 checkpoint({'action':'headless_foreground_launch320','note':'No Activity. Foreground service retains existing registry session during user-requested audit.'})
 act('player.probeSurface',{'enabled':True})
 existing=read_results();refresh_summary()
 for movie in manifest:
  if not can_continue():break
  media_id=movie['mediaId']
  if PHASE=='starts' and media_id in existing:continue
  if PHASE=='matrix' and not existing.get(media_id,{}).get('startup',{}).get('decoded'):continue
  row=existing.get(media_id,dict(movie,variants=[]))
  try:
   try:act('player.stop',media=media_id)
   except RuntimeError:pass
   baseline=req('streams').get('probeFrames',0);started=time.monotonic()
   act('media.play',{'mediaId':media_id,'title':movie['title'],'quality':'Auto','voice':'Auto','recordHistory':False,'persist':False,'resume':False},media_id)
   result,streams=wait_ready(media_id,baseline)
   result['commandToReadyMs']=round((time.monotonic()-started)*1000,2);result['withinFiveSeconds']=result['decoded'] and result['commandToReadyMs']<=5000
   row['startup']=result;row['inventory']=inventory(streams);row['build']=320;row['checkedAt']=now()
   if PHASE=='matrix' and result['decoded']:
    # Wait for the remaining provider fan-out for this phase; no URL is counted as a decode.
    end=time.monotonic()+10;last_count=0;stable=0
    while time.monotonic()<end and can_continue():
     streams=req('streams');count=sum(len(x.get('voices',[])) for x in streams.get('qualities',[]))
     if count==last_count:stable+=1
     else:stable=0;last_count=count
     if stable>=6:break
     time.sleep(.5)
    row['inventory']=inventory(streams)
    pairs=row['inventory']['advertisedPairs']
    for quality,voice in pairs:
     if not can_continue():break
     prior_s,prior_m,prior=observations();baseline=prior_s.get('probeFrames',0)
     variant={'quality':quality,'voice':voice}
     try:
      act('player.selectQuality',{'quality':quality,'persist':False},media_id)
      act('player.selectVoice',{'voice':voice,'persist':False},media_id)
      change,new_s=wait_ready(media_id,baseline,12,quality,voice);variant.update(change)
      o=change.get('observed') or {}
      variant['qualityActualChanged']=change['decoded'] and o.get('height')!=prior.get('height')
      variant['audioTrackIdentityChanged']=change['decoded'] and ((o.get('selectedAudioLabel'),o.get('selectedAudioLanguage'))!=(prior.get('selectedAudioLabel'),prior.get('selectedAudioLanguage')))
      variant['locatorChanged']=change['decoded'] and o.get('locatorFingerprint')!=prior.get('locatorFingerprint')
      variant['studioIndependentlyVerified']=False
      if not change['decoded'] or not change['qualityMatches'] or not change['voiceReportedMatches']:variant['status']='FAIL'
      elif voice!=prior.get('voice') and not variant['audioTrackIdentityChanged']:variant['status']='UNVERIFIED'
      else:variant['status']='PASS'
     except Exception as e:variant.update(status='FAIL',error=str(e)[:120])
     row['variants'].append(variant);save_movie(row)
   save_movie(row)
   print(json.dumps({'mediaId':media_id,'title':movie['title'],'decoded':result['decoded'],'startupMs':result['commandToReadyMs'],'voices':len(row['inventory']['voiceLabels']),'qualities':len(row['inventory']['qualityLabels']),'attempted':SUMMARY['attempted'],'decodedStarted':SUMMARY['decodedStarted']},ensure_ascii=False),flush=True)
  except Exception as e:
   checkpoint({'action':'audit_infrastructure_pause','mediaId':media_id,'error':str(e)[:150]});PAUSE.touch();break
finally:
 try:
  try:act('player.stop')
  except Exception:pass
  act('player.probeSurface',{'enabled':False})
  after=snapshot();assert before['library']==after['library'] and before['settings']==after['settings'],'User data changed'
  refresh_summary();SUMMARY.update(userLibraryPreserved=True,userSettingsPreserved=True,completed=SUMMARY['attempted']>=500 if PHASE=='starts' else False)
  checkpoint({'action':'phase_end','result':dict(SUMMARY)})
 except Exception as e:
  (OUT/'cleanup-error.json').write_text(json.dumps({'error':str(e)[:160],'at':now()}))
 STOP.set();CHANGED.set();thread.join(timeout=50)
 print(json.dumps(SUMMARY,ensure_ascii=False),flush=True)
