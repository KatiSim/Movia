from pathlib import Path
import json,time,uuid,urllib.request,subprocess,os
out=Path.home()/'.cache/movia-architecture-20261002';token=(Path.home()/'.config/movia-agent/token').read_text().strip()
def req(path,body=None):
 q=urllib.request.Request('http://127.0.0.1:8899/agent/v1/'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(q,timeout=12) as p:return json.load(p)
def action(name,args=None):return req('action',{'action':name,'arguments':args or {},'requestId':'block04-'+uuid.uuid4().hex})
result={'case':'post_install_exact_series_decode','mediaId':'159','season':1,'episode':2}
try:
 assert req('snapshot')['app']['uiAttached'] is False
 action('player.probeSurface',{'enabled':True})
 start=time.monotonic();a=action('media.play',{'mediaId':'159','season':1,'episode':2,'voice':'Оригинал (+субтитры)','quality':'720p','persist':False,'recordHistory':False,'resume':False});result['actionStatus']=a.get('status')
 passed=False
 while time.monotonic()-start<50:
  d=req('diagnostics');s=req('streams');m=d.get('media3',{});p=d.get('snapshot',{}).get('playback',{})
  passed=m.get('isPlaying') is True and m.get('switchState')=='READY' and str(m.get('mediaItemId'))=='159' and p.get('durationMs',0)>2000000 and s.get('probeFrames',0)>3
  if passed:break
  time.sleep(.5)
 result.update({'passed':bool(passed),'elapsedSeconds':round(time.monotonic()-start,2),'mediaItemId':m.get('mediaItemId'),'isPlaying':m.get('isPlaying'),'switchState':m.get('switchState'),'durationMs':p.get('durationMs'),'videoHeight':m.get('videoHeight'),'probeFrames':s.get('probeFrames'),'activeVoice':s.get('activeVoice'),'activeQuality':s.get('activeQuality')})
 selected=next((v for q in s.get('qualities',[]) for v in q.get('voices',[]) if v.get('streamId')==s.get('activeStreamId')), {})
 result['activeStreamId']=s.get('activeStreamId')
 result['nativePublicId']=str(s.get('activeStreamId') or '').startswith('provider-item:v2:')
 result['nativeTransport']=(selected.get('transportMetadata') or {}).get('hdrezka_native_transport')
finally:
 try:action('player.stop');action('player.probeSurface',{'enabled':False});result['idleCleanup']=True
 except Exception:result['idleCleanup']=False
 env=dict(os.environ,RISH_APPLICATION_ID='com.termux');p=subprocess.run(['rish','-c','pidof app.movia.android'],capture_output=True,text=True,env=env);pid=(p.stdout+p.stderr).strip().split()[0]
 p=subprocess.run(['rish','-c','logcat -d --pid='+pid],capture_output=True,text=True,env=env);result['crashLines']=[x for x in (p.stdout+p.stderr).splitlines() if 'FATAL EXCEPTION' in x or 'ANR in app.movia.android' in x]
 (out/'block04-direct-decoder-smoke-20261007.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False),flush=True)
