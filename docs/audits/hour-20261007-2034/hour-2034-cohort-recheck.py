from pathlib import Path
import json,time,urllib.request,concurrent.futures,datetime
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2034'
todo=[]
for label in ['s','t']:
 for line in (C/f'cohort-{label}-live-results-20261007.jsonl').read_text().splitlines():
  x=json.loads(line)
  if x.get('outcome')=='pending_or_unconfirmed':todo.append((label,x['mediaId']))
def check(item):
 cohort,media=item;t=time.monotonic()
 def get(refresh=False):
  with urllib.request.urlopen('http://127.0.0.1:8888/api/movie/'+media+'/stream'+('?refresh=1' if refresh else ''),timeout=8) as r:return json.load(r)
 b=get(True);observed=b.get('refreshing',False)
 while time.monotonic()-t<65:
  if not b.get('refreshing') and b.get('discoveryStatus') in ['READY','UNAVAILABLE','ERROR']:break
  time.sleep(1);b=get()
 rows=b.get('streams') or []
 native=[x for x in rows if str(x.get('stream_id') or '').startswith('provider-item:v2:')]
 errors=[x.get('stream_id') for x in rows if str(x.get('catalog_media_id'))!=media]
 return {'cohort':cohort,'mediaId':media,'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':b.get('status'),
  'discoveryStatus':b.get('discoveryStatus'),'refreshing':b.get('refreshing'),'observedActiveDiscovery':observed,
  'foundLeaves':len(rows),'nativeConcreteLeaves':len(native),'identityMismatches':errors,
  'outcome':'pending_or_unconfirmed' if b.get('refreshing') else ('completed_with_candidates' if native else ('provider_unavailable' if b.get('discoveryStatus')=='UNAVAILABLE' else 'unconfirmed')),
  'seconds':round(time.monotonic()-t,2)}
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
 results=list(pool.map(check,todo))
(D/'existing-cohort-pending-followup.json').write_text(json.dumps({'newSample':False,'results':results},indent=2))
print(json.dumps(results),flush=True)
