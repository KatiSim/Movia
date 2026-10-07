from pathlib import Path
import json,time,uuid,urllib.request,subprocess,os
out=Path.home()/'.cache/movia-architecture-20261002';token=(Path.home()/'.config/movia-agent/token').read_text().strip()
def req(path,body=None):
 q=urllib.request.Request('http://127.0.0.1:8899/agent/v1/'+path,data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 with urllib.request.urlopen(q,timeout=12) as p:return json.load(p)
def action(name,args=None):return req('action',{'action':name,'arguments':args or {},'requestId':'block04-'+uuid.uuid4().hex})
result={'case':'post_install_exact_v2_torrent_decode','mediaId':'158'}
try:
 assert req('snapshot')['app']['uiAttached'] is False
 action('player.probeSurface',{'enabled':True})
 with urllib.request.urlopen('http://127.0.0.1:8888/api/movie/158/stream',timeout=15) as q: inventory=json.load(q)
 rows=[r for r in inventory['streams'] if str(r.get('stream_id','')).startswith('provider-item:v2:') and r.get('quality')=='1080p']
 rows.sort(key=lambda r:int(r.get('seeders') or 0),reverse=True);chosen=rows[0];result['requestedStreamId']=chosen['stream_id']
 start=time.monotonic();a=action('media.play',{'mediaId':'158','streamId':chosen['stream_id'],'voice':chosen['voice'],'quality':chosen['quality'],'persist':False,'recordHistory':False,'resume':False});result['actionStatus']=a.get('status')
 passed=False
 while time.monotonic()-start<100:
  try: d=req('diagnostics');s=req('streams')
  except urllib.error.HTTPError as error:
   body=error.read().decode(errors='replace');result.setdefault('diagnosticHttpErrors',[]).append({'code':error.code,'body':body[:300]});time.sleep(.5);continue
  m=d.get('media3',{});p=d.get('snapshot',{}).get('playback',{})
  passed=m.get('isPlaying') is True and m.get('switchState')=='READY' and str(m.get('mediaItemId'))=='158' and s.get('activeStreamId')==chosen['stream_id'] and p.get('durationMs',0)>9000000 and s.get('probeFrames',0)>3
  if passed:break
  time.sleep(.5)
 result.update({'passed':bool(passed),'elapsedSeconds':round(time.monotonic()-start,2),'mediaItemId':m.get('mediaItemId'),'isPlaying':m.get('isPlaying'),'switchState':m.get('switchState'),'durationMs':p.get('durationMs'),'videoHeight':m.get('videoHeight'),'probeFrames':s.get('probeFrames'),'activeVoice':s.get('activeVoice'),'activeQuality':s.get('activeQuality')})
 selected=next((v for q in s.get('qualities',[]) for v in q.get('voices',[]) if v.get('streamId')==s.get('activeStreamId')), {})
 result['activeStreamId']=s.get('activeStreamId')
 result['nativePublicId']=str(s.get('activeStreamId') or '').startswith('provider-item:v2:')
 result['transportMetadataKeys']=sorted((selected.get('transportMetadata') or {}).keys())
finally:
 try:action('player.stop');action('player.probeSurface',{'enabled':False});result['idleCleanup']=True
 except Exception:result['idleCleanup']=False
 env=dict(os.environ,RISH_APPLICATION_ID='com.termux');p=subprocess.run(['rish','-c','pidof app.movia.android'],capture_output=True,text=True,env=env);pid=(p.stdout+p.stderr).strip().split()[0]
 p=subprocess.run(['rish','-c','logcat -d --pid='+pid],capture_output=True,text=True,env=env);result['crashLines']=[x for x in (p.stdout+p.stderr).splitlines() if 'FATAL EXCEPTION' in x or 'ANR in app.movia.android' in x]
 (out/'block04-decoder-smoke-20261007.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False),flush=True)
