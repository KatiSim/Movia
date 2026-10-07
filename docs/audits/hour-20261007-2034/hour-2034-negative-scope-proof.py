from pathlib import Path
import json,urllib.request,urllib.error,time,datetime
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2034'
token=(Path.home()/'.config/movia-agent/token').read_text().strip()
def req(path,payload=None):
 q=urllib.request.Request('http://127.0.0.1:8888/'+path,data=json.dumps(payload).encode() if payload is not None else None,
 headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(q,timeout=8) as r:return r.status,json.load(r)
 except urllib.error.HTTPError as e:
  return e.code,json.loads(e.read() or '{}')
def facts(row):
 t=row.get('sourceTruth') or {}
 return {k:t.get(k) for k in ['sourceId','verificationStatus','verificationMethod','lastSuccessAt','lastFailureAt','consecutiveFailures']}
_,body=req('api/movie/1168/stream')
rows=[s for s in body.get('streams',[]) if str(s.get('stream_id') or '').startswith('provider-item:v2:') and s.get('sourceId')]
row=next((s for s in rows if s.get('stream_id')=='provider-item:v2:5bb7aa1f9c86016954c1ee85'),rows[0] if rows else {})
assert row,'No indexed native leaf for non-mutating rejection proof'
sid=row['stream_id'];before=facts(row);meta=row.get('transport_metadata') or {}
payload={'sourceId':row['sourceId'],'reason':'NETWORK','observedAt':time.time(),
 'locatorHash':meta['native_feedback_locator_hash'],'profileHash':'0'*64}
status,response=req('internal/playback-availability/media3-failure',payload)
_,body=req('api/movie/1168/stream');after=facts(next(s for s in body.get('streams',[]) if s.get('stream_id')==sid))
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mediaId':'1168','streamId':sid,
 'httpStatus':status,'expectedStaleScopeRejected':status==400,'before':before,'after':after,
 'sourceStateUnchanged':before==after,'installedApkSha256':json.loads((C/'hour-2034-offline-install-cold.json').read_text())['installedSha256']}
out['passed']=out['expectedStaleScopeRejected'] and out['sourceStateUnchanged']
(D/'negative-scope-live-proof.json').write_text(json.dumps(out,indent=2));print(json.dumps(out),flush=True)
raise SystemExit(0 if out['passed'] else 1)
