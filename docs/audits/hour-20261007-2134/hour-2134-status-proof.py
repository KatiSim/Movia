"""Observe status propagation on real prior no-native cards; no injected failures."""
from pathlib import Path
import json,time,sys
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2134'
sys.path.insert(0,str(D))
from runtime_helpers import req
records=[json.loads(x) for x in (D/'android_coverage_results.jsonl').read_text().splitlines() if x.strip()]
latest={x['mediaId']:x for x in records}
targets=[x for x in latest.values() if x.get('outcome')=='NO_NATIVE_TERMINAL'][:3]
out={'at':time.time(),'realCatalogResponses':True,'cases':[],'playerActions':False}
for item in targets:
 media=item['mediaId'];se=item.get('season');ep=item.get('episode')
 path='api/movie/'+media+'/stream'+('?season='+str(se)+'&episode='+str(ep) if se is not None else '')
 states=[];start=time.monotonic();identity=True
 body=req(path+('&' if '?' in path else '?')+'refresh=1',port=8888)
 while time.monotonic()-start<32:
  identity &= str(body.get('mediaId'))==media and (se is None or (body.get('season'),body.get('episode'))==(se,ep))
  state={k:body.get(k) for k in ['status','discoveryStatus','refreshing','providerErrorCount','pendingProviderCount','providerStatuses','errorCode','discoveryError']}
  if not states or state!=states[-1]:states.append(state)
  if not body.get('refreshing') and state.get('providerStatuses') is not None:break
  time.sleep(.5);body=req(path,port=8888)
 count=len([x for x in body.get('streams',[]) if str(x.get('stream_id') or '').startswith('provider-item:v2:')])
 case={'mediaId':media,'season':se,'episode':ep,'identityPreserved':identity,'states':states,'nativeCandidates':count,'seconds':round(time.monotonic()-start,2)}
 case['errorWasNotClassifiedAsUnavailability']=not (body.get('providerErrorCount') or 0) or body.get('discoveryStatus') in {'ERROR','PENDING'}
 out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
out['allIdentityPreserved']=all(x['identityPreserved'] for x in out['cases'])
out['providerErrorsObserved']=sum(any((s.get('providerErrorCount') or 0)>0 for s in x['states']) for x in out['cases'])
out['allObservedErrorsSeparated']=all(x['errorWasNotClassifiedAsUnavailability'] for x in out['cases'])
(D/'typed-discovery-live-proof.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
print(json.dumps({k:out[k] for k in ['allIdentityPreserved','providerErrorsObserved','allObservedErrorsSeparated']}),flush=True)
