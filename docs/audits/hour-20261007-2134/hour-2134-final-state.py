from pathlib import Path
import json,urllib.request,subprocess,os,shlex,hashlib,re,datetime
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';D=R/'docs/audits/hour-20261007-2134'
token=(Path.home()/'.config/movia-agent/token').read_text().strip()
def req(path,port=8899):
 q=urllib.request.Request('http://127.0.0.1:'+str(port)+('/agent/v1/' if port==8899 else '/')+path,headers={'Authorization':'Bearer '+token})
 with urllib.request.urlopen(q,timeout=8) as r:return json.load(r)
snap=req('snapshot');diag=req('diagnostics')
env=dict(os.environ,RISH_APPLICATION_ID='com.termux')
def shell(cmd):
 p=subprocess.run(['rish','-c',cmd],env=env,capture_output=True,text=True,timeout=15)
 return p.stdout+'\n'+p.stderr
package=shell('pm path app.movia.android')
paths=[x.strip()[8:] for x in package.splitlines() if x.strip().startswith('package:')]
assert paths,package
installed=shell('sha256sum '+shlex.quote(paths[0]))
installed_sha=next(x.split()[0] for x in installed.splitlines() if re.match(r'^[0-9a-f]{64}\s',x))
wanted=json.loads((C/'hour-2134-install-cold.json').read_text())['builtSha256']
pid=shell('pidof app.movia.android').strip().split()
pid=next((x for x in pid if x.isdigit()),None)
assert pid,'Headless Movia process absent'
crash=shell('logcat -d -b crash --pid='+pid+' -v brief -t 150')
anr=shell('logcat -d -b events -s am_anr -v brief -t 150')
errors=[x for x in crash.splitlines() if 'FATAL EXCEPTION' in x or 'ANR in app.movia.android' in x]
errors.extend(x for x in anr.splitlines() if 'app.movia.android' in x)
names=['catalog_stream_service.py','media_content_probe.py','native_variant_feedback.py','playback_availability_index.py','source_playback_evidence.py','streamer.py','discovery_queue.py','discovery_outcome.py','provider_discovery.py']
live=Path.home()/'projects/media-parser'
parity={n:hashlib.sha256((R/'backend/runtime'/n).read_bytes()).hexdigest()==hashlib.sha256((live/n).read_bytes()).hexdigest() for n in names}
services={}
for name in ['movia-media-parser','movia-stream-enricher']:
 p=subprocess.run(['sv','status','/data/data/com.termux/files/usr/var/service/'+name],capture_output=True,text=True,timeout=10)
 services[name]={'exitCode':p.returncode,'status':p.stdout.strip()}
with urllib.request.urlopen('http://127.0.0.1:8888/health',timeout=5) as r:health=r.status
base=json.loads((C/'block08-dirty-baseline.json').read_text())
changed=[n for n,h in base.items() if not (R/n).exists() or hashlib.sha256((R/n).read_bytes()).hexdigest()!=h]
initial=json.loads((D/'final-installed-proof.json').read_text())['initialCounts']
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'installedSha256':installed_sha,
 'builtSha256':wanted,'shaMatches':installed_sha==wanted,'app':snap['app'],'playback':snap['playback'],
 'playerIdle':snap['playback'].get('status')=='IDLE' and snap['playback'].get('switchState')=='IDLE' and snap['playback'].get('playing') is False,
 'libraryCounts':snap['library']['counts'],'libraryCountsPreserved':snap['library']['counts']==initial,
 'nativeArchitecture':diag.get('legacyEngine',{}).get('architecture'),
 'referenceRuntimeLoaded':diag.get('legacyEngine',{}).get('referenceRuntimeLoaded'),
 'backendHealth':health,'services':services,'runtimeParity':parity,'crashOrAnrLines':errors,
 'unrelatedFilesPreserved':len(base),'unrelatedChangedFiles':changed}
out['passed']=out['playerIdle'] and out['referenceRuntimeLoaded'] is False and out['shaMatches'] and health==200 and all(parity.values()) and not errors and not changed and snap['app']['uiAttached'] is False and out['libraryCountsPreserved']
(D/'final-state.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(json.dumps(out,ensure_ascii=False),flush=True)
raise SystemExit(0 if out['passed'] else 1)
