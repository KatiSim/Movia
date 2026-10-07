"""Observe real prior failures; never create sources or manufacture decoder errors."""
from pathlib import Path
import json,time,sys
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2134'
sys.path.insert(0,str(D))
from runtime_helpers import req,act,rows
previous=[json.loads(x) for x in (D/'android_coverage_results.jsonl').read_text().splitlines() if x.strip()]
targets=[x for x in previous if x.get('outcome')=='PLAYER_FAILED'][:4]
out={'at':time.time(),'installedApkSha256':json.loads((C/'hour-2134-install-cold.json').read_text())['installedSha256'],'cases':[]}
def card(media,se=None,ep=None):
 return req('api/movie/'+media+'/stream'+('?season='+str(se)+'&episode='+str(ep) if se is not None else ''),port=8888)
def evidence(row):
 t=row.get('sourceTruth') or {};meta=row.get('transport_metadata') or {}
 return {k:t.get(k) for k in ['verificationStatus','lastFailureAt','lastSuccessAt','consecutiveFailures']},(meta.get('native_feedback_locator_hash'),meta.get('native_feedback_profile_hash'))
try:
 snap=req('snapshot');assert not snap['app']['uiAttached'];out['initialCounts']=snap['library']['counts']
 act('player.probeSurface',{'enabled':True})
 for item in targets:
  if len([x for x in out['cases'] if x.get('playbackAttempted')])>=2:break
  media=item['mediaId'];se=item.get('season');ep=item.get('episode')
  body=card(media,se,ep)
  native=[x for x in body.get('streams',[]) if str(x.get('stream_id') or '').startswith('provider-item:v2:')]
  row=next((x for x in native if x.get('stream_id')==item.get('requestedStreamId')),None)
  if row is None:
   out['cases'].append({'mediaId':media,'attempted':False,'reason':'PRIOR_NATIVE_LEAF_NOT_CURRENT','status':body.get('status')});continue
  before,scope=evidence(row);sid=row['stream_id']
  case={'mediaId':media,'season':se,'episode':ep,'requestedStreamId':sid,'sourceIdPresentBefore':bool(row.get('sourceId')),'before':before,'playbackAttempted':True}
  act('player.stop');frames=req('streams').get('probeFrames',0);start=time.monotonic();event=time.time()-1
  args={'mediaId':media,'streamId':sid,'quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False}
  if se is not None:args.update(season=se,episode=ep)
  act('media.play',args)
  observed_error=False;identity_ok=True
  while time.monotonic()-start<45:
   diag=req('diagnostics');streams=req('streams');m=diag.get('media3',{});p=diag.get('snapshot',{}).get('playback',{});sel=diag.get('streamSelection') or {}
   identity_ok &= str(p.get('mediaId'))==media and (se is None or (p.get('season'),p.get('episode'))==(se,ep))
   reason=sel.get('fallbackReason') or ''
   observed_error |= any(x in reason for x in ['TIMEOUT','ERROR','FAILED','NON_NETWORK','RELOADED'])
   case['lastPlayback']={'state':m.get('switchState'),'height':m.get('videoHeight'),'frames':streams.get('probeFrames'),'activeId':streams.get('activeStreamId'),'fallbackReason':reason}
   if (m.get('isPlaying') and streams.get('probeFrames',0)>frames+4 and m.get('videoHeight',0)>0) or m.get('switchState')=='FAILED':break
   time.sleep(.5)
  case['seconds']=round(time.monotonic()-start,2);case['identityPreserved']=identity_ok
  until=time.monotonic()+6;after={};after_scope=(None,None)
  while time.monotonic()<until:
   b=card(media,se,ep);fresh=next((x for x in b.get('streams',[]) if x.get('stream_id')==sid),{})
   after,after_scope=evidence(fresh)
   if after_scope!=scope or (after.get('lastFailureAt') or 0)>=event:break
   time.sleep(.5)
  case.update(after=after,samePhysicalScope=scope==after_scope,actualFailureObserved=observed_error)
  case['failureFeedbackConfirmed']=observed_error and identity_ok and scope==after_scope and (after.get('lastFailureAt') or 0)>=event and (after.get('consecutiveFailures') or 0)>(before.get('consecutiveFailures') or 0)
  out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
except Exception as error:
 out['harnessError']=type(error).__name__
finally:
 act('player.stop');act('player.probeSurface',{'enabled':False})
 snap=req('snapshot');out['finalCounts']=snap['library']['counts'];out['countsPreserved']=out.get('initialCounts')==out['finalCounts']
 out['realFailureFeedbackObserved']=any(x.get('failureFeedbackConfirmed') for x in out['cases'])
 out['unindexedFailureFeedbackObserved']=any(x.get('failureFeedbackConfirmed') and not x.get('sourceIdPresentBefore') for x in out['cases'])
 (D/'real-failure-feedback.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(json.dumps({'realFailureFeedbackObserved':out['realFailureFeedbackObserved'],'unindexedFailureFeedbackObserved':out['unindexedFailureFeedbackObserved'],'countsPreserved':out['countsPreserved']}),flush=True)
