from pathlib import Path
import hashlib,json,os,subprocess,time,urllib.request,uuid,sys
OUT=Path.home()/'.cache/movia-architecture-20261002';sys.path.insert(0,str(OUT))
from journal import record
TOKEN=(Path.home()/'.config/movia-agent/token').read_text().strip()
def req(path,body=None):
 data=json.dumps(body).encode() if body is not None else None
 with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:8899/agent/v1/'+path,data=data,headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'}),timeout=6) as r:return json.load(r)
def act(a,v=None):
 r=req('action',{'action':a,'arguments':v or {},'requestId':'trace-'+uuid.uuid4().hex})
 if r.get('status') not in ['accepted','completed']:raise RuntimeError('ACTION_'+str(r.get('code')))
 return r
before=req('snapshot');assert before['app']['uiAttached'] is False and before['app']['versionCode']==323
subprocess.run(['rish','-c','am start-foreground-service -n app.movia.android/.agent.AgentAuditService'],env=dict(os.environ,RISH_APPLICATION_ID='com.termux'),capture_output=True,timeout=15,check=True)
rows=[];our_ids={'164','375'}
try:
 act('player.probeSurface',{'enabled':True})
 for mid in ['164','375','164']:
  if req('snapshot')['app']['uiAttached']:raise RuntimeError('USER_UI_ATTACHED')
  report=json.loads((OUT/'regression7-source-current'/(mid+'.json')).read_text());sources=json.loads((OUT/'regression7-source-current-private'/(mid+'.json')).read_text())
  target=next(s for s in sources if hashlib.sha256(str(s.get('stream_id')).encode()).hexdigest()[:20]==report['preparedStreamIdFingerprint'])
  act('player.stop');baseline=req('streams').get('probeFrames',0);start=time.monotonic()
  act('media.play',{'mediaId':mid,'title':report['title'],'streamId':target['stream_id'],'quality':'Auto','voice':'Auto','persist':False,'resume':False,'recordHistory':False})
  samples=[];last_sig=None;good_frames=None
  while time.monotonic()-start<35:
   d=req('diagnostics');m=d['media3'];s=req('streams')
   row={k:m.get(k) for k in ['playbackState','switchState','isPlaying','playWhenReady','playbackSuppressionReason','playerErrorCode','playerErrorCause','mediaItemId','videoHeight','bufferedPositionMs','currentPositionMs','selectedAudioLabel','selectedAudioLanguage']}
   row.update(ms=round((time.monotonic()-start)*1000),frames=s.get('probeFrames',0)-baseline,audioTracks=len(s.get('audioTracks',[])),videoTracks=len(s.get('videoTracks',[])),readyLatencyMs=s.get('readyLatencyMs'),firstFrameLatencyMs=s.get('firstFrameLatencyMs'))
   sig=(row['playbackState'],row['switchState'],row['isPlaying'],row['videoHeight'],row['selectedAudioLabel'],row['bufferedPositionMs']//1000,row['playerErrorCode'])
   if sig!=last_sig or not samples or row['ms']-samples[-1]['ms']>1000:samples.append(row);last_sig=sig
   if row['isPlaying'] and row['switchState']=='READY':
    if good_frames is None:good_frames=s.get('probeFrames',0)
    if s.get('probeFrames',0)>=good_frames+3:break
   time.sleep(.25)
  result={'mediaId':mid,'attempt':len(rows)+1,'build':323,'stableFrames':good_frames is not None and s.get('probeFrames',0)>=good_frames+3,'samples':samples}
  rows.append(result);print(json.dumps({'mediaId':mid,'attempt':len(rows),'stableFrames':result['stableFrames'],'last':samples[-1]}),flush=True)
finally:
 current=req('snapshot');ours=current.get('playback',{}).get('mediaId') in our_ids
 if current['app']['uiAttached'] is False:
  act('player.stop');act('player.probeSurface',{'enabled':False})
 after=req('snapshot');result={'rows':rows,'userLibraryPreserved':before['library']==after['library'],'userSettingsPreserved':before['settings']==after['settings'],'phoneUiInteraction':False}
 p=OUT/'startup-trace323.json';p.write_text(json.dumps(result,ensure_ascii=False,indent=2));record('native_startup_buffer_trace',{'attempted':len(rows),'stableStarts':sum(x['stableFrames'] for x in rows),'uiTouched':False,'userLibraryPreserved':result['userLibraryPreserved'],'userSettingsPreserved':result['userSettingsPreserved']},artifacts=[(p,p.name)])
