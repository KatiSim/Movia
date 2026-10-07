from pathlib import Path
import json,time
C=Path.home()/'.cache/movia-architecture-20261002'
import sys
D=C/'repo/docs/audits/hour-20261007-2134'
sys.path.insert(0,str(D))
from runtime_helpers import req,act,wait,rows
out={'at':time.time(),'cases':[],'installedApkSha256':json.loads((C/'hour-2134-install-cold.json').read_text())['builtSha256']}
def read_proof(media,sid,se=None,ep=None):
 path='api/movie/'+media+'/stream'+('?season='+str(se)+'&episode='+str(ep) if se else '')
 b=req(path,port=8888)
 row=next((x for x in b.get('streams',[]) if x.get('stream_id')==sid),{})
 truth=row.get('sourceTruth') or {}
 return {'status':b.get('status'),'sourceIdPresent':bool(row.get('sourceId')),
  'decoded':truth.get('decodedPlayback'),'actualQuality':truth.get('actualQuality'),
  'lastSuccessAt':truth.get('lastSuccessAt'),'verificationMethod':truth.get('verificationMethod')}
try:
 snap=req('snapshot');assert snap['app']['uiAttached'] is False
 out['initialCounts']=snap['library']['counts']
 act('player.probeSurface',{'enabled':True})
 targets=[
 ('26041','provider-item:v2:045fb4de1875f54020947bf8:8ea4e1f6fd8787bb7bab5a2e',None,None),
 ('1168','provider-item:v2:5bb7aa1f9c86016954c1ee85',None,None),
 ('159','provider-item:v2:016cafe2eca821dc1ef9128f',1,2)]
 for media,sid,se,ep in targets:
  act('player.stop');before=req('streams').get('probeFrames',0);preflight=time.monotonic()
  previous=read_proof(media,sid,se,ep)
  preflight_seconds=time.monotonic()-preflight;started=time.monotonic();event_after=time.time()-1
  args={'mediaId':media,'streamId':sid,'quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False}
  if se:args.update(season=se,episode=ep)
  response=act('media.play',args)
  case=wait(media,sid,se,ep,seconds=35,before=before)
  case.update(case='nativeFeedback-'+media,action=response,backendBefore=previous,preflightSeconds=round(preflight_seconds,2),requestToDecodedSeconds=round(time.monotonic()-started,2))
  until=time.monotonic()+7
  proof={}
  while case['passed'] and time.monotonic()<until:
   proof=read_proof(media,sid,se,ep)
   if proof.get('decoded') is True and (proof.get('lastSuccessAt') or 0)>=event_after:break
   time.sleep(.4)
  attached_until=time.monotonic()+6
  attached=False
  while case['passed'] and time.monotonic()<attached_until:
   snapshot=req('streams')
   candidate=next((x for x in rows(snapshot) if x.get('streamId')==sid),{})
   attached=bool(candidate.get('sourceId')) and snapshot.get('activeStreamId')==sid
   if attached:break
   time.sleep(.3)
  case['sourceIdAttachedToActiveOptionsAfterPolling']=attached
  case['sourceIdVisibleOnDecodedFrame']=case.get('sourceIdPresent') is True
  case['sourceIdCheckPassed']=attached or (previous.get('sourceIdPresent') is True and case.get('sourceIdPresent') is True)
  case['backendAfter']=proof
  case['freshFeedbackPassed']=case['passed'] and case['sourceIdCheckPassed'] and proof.get('decoded') is True and proof.get('actualQuality')==str(case.get('height'))+'p' and (proof.get('lastSuccessAt') or 0)>=event_after
  out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
 # Verify selection retains a known measured quality after fresh discovery.
 req('api/movie/159/stream?season=1&episode=2&refresh=1',port=8888);time.sleep(4)
 before=req('streams').get('probeFrames',0);act('player.seek',{'positionMs':30000})
 response=act('player.selectQuality',{'quality':'480p','persist':False})
 case=wait('159',targets[-1][1],1,2,seconds=20,before=before)
 case.update(case='measured480AfterRediscovery',action=response,positionPreserved=case.get('positionMs',0)>=29000)
 case['passed']=case['passed'] and response.get('status')=='completed' and case['positionPreserved']
 out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
except Exception as error:
 out['harnessError']=type(error).__name__
finally:
 act('player.stop');act('player.probeSurface',{'enabled':False})
 snap=req('snapshot');out['finalCounts']=snap['library']['counts'];out['finalPlayback']=snap['playback']
 out['allRequiredPassed']=len(out['cases'])==4 and not out.get('harnessError') and all(c.get('freshFeedbackPassed',c.get('passed')) for c in out['cases']) and out['initialCounts']==out['finalCounts']
 (D/'final-installed-proof.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
 print(json.dumps({'allRequiredPassed':out['allRequiredPassed'],'caseCount':len(out['cases']),'countsPreserved':out['initialCounts']==out['finalCounts']}),flush=True)

raise SystemExit(0 if out['allRequiredPassed'] else 1)
