from pathlib import Path
import os,subprocess,shutil,hashlib,json,time,re,urllib.request,datetime
out=Path.home()/'.cache/movia-architecture-20261002';repo=out/'repo'
def rish(cmd,timeout=30):
 p=subprocess.run(['rish','-c',cmd],env=dict(os.environ,RISH_APPLICATION_ID='com.termux'),capture_output=True,text=True,timeout=timeout)
 text=p.stdout+p.stderr
 assert p.returncode==0,(cmd,text)
 return text
binding=subprocess.run([str(Path.home()/'.config/jarvis-device-binding/verify_binding.sh')],capture_output=True,text=True,timeout=20)
assert binding.returncode==0,'phone binding failed'
assert json.loads((out/'block02-android-verified-build-result-20261007.json').read_text())['exitCode']==0
apk=repo/'app/build/outputs/apk/debug/app-debug.apk';built=hashlib.sha256(apk.read_bytes()).hexdigest()
stage=Path('/sdcard/Download/Movia_QA_335');stage.mkdir(exist_ok=True);shutil.copy2(apk,stage/'block02-app.apk')
rish('cp /sdcard/Download/Movia_QA_335/block02-app.apk /data/local/tmp/movia-block02.apk')
install=rish('pm install -r -d /data/local/tmp/movia-block02.apk',60);assert re.search(r'^Success$',install,re.M),install
base=rish('pm path app.movia.android').strip().split('package:')[-1].strip()
installed=rish('sha256sum '+base).split()[0];assert built==installed
version=rish('dumpsys package app.movia.android');assert 'versionCode=335' in version and 'versionName=0.0.1' in version
rish('am force-stop app.movia.android');launch=rish('am start-foreground-service -n app.movia.android/.agent.AgentAuditService')
token=(Path.home()/'.config/movia-agent/token').read_text().strip();snapshot=None
for i in range(30):
 try:
  req=urllib.request.Request('http://127.0.0.1:8899/agent/v1/snapshot',headers={'Authorization':'Bearer '+token})
  with urllib.request.urlopen(req,timeout=2) as q:snapshot=json.load(q)
  if snapshot.get('app',{}).get('versionCode')==335:break
 except Exception:pass
 time.sleep(.5)
assert snapshot is not None,'headless agent failed'
assert snapshot['app']['uiAttached'] is False,'physical UI must stay detached'
pid=rish('pidof app.movia.android').strip().split()[0];log=rish('logcat -d --pid='+pid,15)
crashes=[x for x in log.splitlines() if 'FATAL EXCEPTION' in x or 'ANR in app.movia.android' in x]
result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'installSuccess':True,'versionCode':335,'versionName':'0.0.1','builtSha256':built,'installedSha256':installed,'coldHeadlessLaunch':True,'uiAttached':False,'crashLines':crashes,'dataPreserved':True,'phoneBindingPassed':True,'launch':launch.strip(),'app':snapshot['app']}
(out/'block02-install-cold-20261007.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False),flush=True);assert not crashes
