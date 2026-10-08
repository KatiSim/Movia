from pathlib import Path
import json,time
C=Path.home()/'.cache/movia-architecture-20261002'
D=C/'repo/docs/audits/hour-20261008-0850'
import sys
sys.path.insert(0,str(D))
from runtime_helpers import req,act,wait
out={'autoCriterion':'Preserves requested Auto and decodes an actual positive height; no highest-quality claim.', 'at':time.time(),'cases':[],'installedApkSha256':json.loads((C/'hour-0850-install-cold.json').read_text())['installedSha256']}
low='provider-item:v2:a03531516b353e38601ba3eb'
try:
 start=req('snapshot');assert start['app']['uiAttached'] is False;out['initialCounts']=start['library']['counts']
 act('player.probeSurface',{'enabled':True});act('player.stop');before=req('streams').get('probeFrames',0)
 reply=act('media.play',{'mediaId':'159','season':1,'episode':2,'streamId':low,'quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False})
 case=wait('159',low,1,2,seconds=35,before=before);case.update(case='exact240Start',action=reply,sourceLoadEvidence=req('diagnostics').get('sourceLoadEvidence'))
 case['passed']=case['passed'] and case.get('height')==240;out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
 if case['passed']:
  original_voice=(case.get('selection') or {}).get('activeVoice');act('player.seek',{'positionMs':30000})
  for quality,height in [('480p',480),('240p',240),('Auto',None)]:
   before=req('streams').get('probeFrames',0);reply=act('player.selectQuality',{'quality':quality,'persist':False})
   case=wait('159',None,1,2,seconds=35,before=before);case.update(case='select-'+quality,action=reply,sourceLoadEvidence=req('diagnostics').get('sourceLoadEvidence'))
   case['requestedQualityPreserved']=(case.get('selection') or {}).get('requestedQuality')==quality
   case['providerVoiceBranchPreserved']=(case.get('selection') or {}).get('activeVoice')==original_voice
   case['positionPreserved']=case.get('positionMs',0)>=29000
   case['passed']=case['passed'] and (case.get('height')==height if height is not None else case.get('height',0)>0) and case['requestedQualityPreserved'] and case['providerVoiceBranchPreserved'] and case['positionPreserved']
   out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
 else:
  out['switchesNotRun']='Exact 240p did not decode within the bounded wait; a 480p fallback is not a 240->480 switch proof.'
finally:
 act('player.stop');act('player.probeSurface',{'enabled':False});end=req('snapshot')
 out['countsPreserved']=end['library']['counts']==out['initialCounts']
 out['allRequiredPassed']=len(out['cases'])==4 and all(x.get('passed') for x in out['cases']) and out['countsPreserved']
 (D/'quality-switch-matrix.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(json.dumps({'allRequiredPassed':out['allRequiredPassed'],'caseCount':len(out['cases'])}),flush=True)
