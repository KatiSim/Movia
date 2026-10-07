from pathlib import Path
import hashlib,shutil,subprocess,json,urllib.request,datetime,sqlite3,time
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo';live=Path.home()/'projects/media-parser'
text=(C/'hour-2034-backend-tests.txt').read_text()
assert 'Ran 523 tests' in text and '\nOK\n' in text
names=['catalog_stream_service.py','media_content_probe.py','native_variant_feedback.py','playback_availability_index.py','streamer.py','source_playback_evidence.py']
backup=C/'hour-2034-backend-backup';backup.mkdir(exist_ok=True)
for name in names:
 if (live/name).exists() and not (backup/name).exists():shutil.copy2(live/name,backup/name)
 shutil.copy2(R/'backend/runtime'/name,live/name)
services=Path('/data/data/com.termux/files/usr/var/service')
states={}
for name in ['movia-media-parser','movia-stream-enricher']:
 p=subprocess.run(['sv','-w','35','restart',str(services/name)],capture_output=True,text=True,timeout=45)
 states[name]={'exitCode':p.returncode,'status':p.stdout.strip()}
 assert p.returncode==0,(name,p.stdout,p.stderr)
health=None
for attempt in range(20):
 try:
  with urllib.request.urlopen('http://127.0.0.1:8888/health',timeout=2) as response:health=response.status
  break
 except Exception:time.sleep(.5)
parity={name:hashlib.sha256((live/name).read_bytes()).hexdigest()==hashlib.sha256((R/'backend/runtime'/name).read_bytes()).hexdigest() for name in names}
assert all(parity.values()) and health==200
result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'health':health,'services':states,'runtimeParity':parity,'tests':523,'schemaChange':'Additive request_profile_hash; existing data retained, old measurements require a matching profile.'}
(C/'hour-2034-backend-deploy.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
