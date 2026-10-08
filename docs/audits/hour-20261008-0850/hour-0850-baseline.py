from pathlib import Path
import subprocess,os,urllib.request,json,datetime,hashlib,shutil
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';D=R/'docs/audits/hour-20261008-0850';D.mkdir(parents=True,exist_ok=True)
token=(Path.home()/'.config/movia-agent/token').read_text().strip()
def shell(cmd):
 p=subprocess.run(['rish','-c',cmd],env=dict(os.environ,RISH_APPLICATION_ID='com.termux'),capture_output=True,text=True,timeout=12)
 assert p.returncode==0,p.stderr
 return p.stdout+p.stderr
base=shell('pm path app.movia.android').strip().split('package:')[-1].strip();sha=shell('sha256sum '+base).split()[0]
def req(path,port=8899):
 q=urllib.request.Request('http://127.0.0.1:'+str(port)+('/agent/v1/' if port==8899 else '/')+path,headers={'Authorization':'Bearer '+token})
 with urllib.request.urlopen(q,timeout=5) as f:return json.load(f)
try:snap=req('snapshot')
except Exception:
 shell('am start-foreground-service -n app.movia.android/.agent.AgentAuditService')
 import time;time.sleep(1);snap=req('snapshot')
base_inputs=json.loads((C/'block08-dirty-baseline.json').read_text());changed=[n for n,h in base_inputs.items() if hashlib.sha256((R/n).read_bytes()).hexdigest()!=h]
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'requestedAt':'2026-10-08T06:50:53Z','hourEnd':'2026-10-08T07:50:53Z','baseCheckpoint':3442,'baseCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=R,text=True).strip(),'installedSha256':sha,'app':snap['app'],'playback':snap['playback'],'libraryCounts':snap['library']['counts'],'unrelatedChangedFiles':changed,'backendHealth':req('health',8888)}
assert out['baseCommit']=='b192f5664ff758a1f5f2c1cc6e9b90f94d6542b8';assert not changed;assert snap['app']['uiAttached'] is False;assert snap['playback']['status']=='IDLE';assert sha=='7592ab9e479a6dcd8f15a7f6eea8f521ef6f118b6c9c1febfe0aae9ddad23ddb'
apk=R/'app/build/outputs/apk/debug/app-debug.apk';assert hashlib.sha256(apk.read_bytes()).hexdigest()==sha;shutil.copy2(apk,C/'hour-0850-rollback-3442.apk')
(D/'baseline.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));shutil.copy2(R/'docs/audits/hour-20261007-2134/runtime_helpers.py',D/'runtime_helpers.py');print(json.dumps(out,ensure_ascii=False))
