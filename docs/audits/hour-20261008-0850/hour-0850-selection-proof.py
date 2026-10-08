"""Test manual selection after a genuine fallback chosen from current-build audit."""
from pathlib import Path
import json,time,sys,datetime
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261008-0850'
sys.path.insert(0,str(D))
from runtime_helpers import req,act,wait
sha=json.loads((C/'hour-0850-install-cold.json').read_text())['installedSha256']
records=[json.loads(x) for x in (D/'android_coverage_results.jsonl').read_text().splitlines() if x.strip()]
choices=[x for x in reversed(records) if x.get('buildSha256')==sha and x.get('outcome')=='DECODED_FALLBACK'][:3]
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'installedApkSha256':sha,'cases':[],
 'criterion':'Observe actual fallback, then Auto and existing decoded height must adopt prepared leaf and preserve exact identity/voice/position. Does not prove a different-quality transition.'}
try:
    start=req('snapshot');assert start['app']['uiAttached'] is False
    out['initialCounts']=start['library']['counts'];act('player.probeSurface',{'enabled':True})
    for item in choices:
        remaining=datetime.datetime(2026,10,8,7,47,20,tzinfo=datetime.timezone.utc).timestamp()-time.time()
        if remaining<40:break
        media=item['mediaId'];se=item.get('season');ep=item.get('episode');sid=item['requestedStreamId']
        act('player.stop');before=req('streams').get('probeFrames',0)
        args={'mediaId':media,'streamId':sid,'quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False}
        if se is not None:args.update(season=se,episode=ep)
        reply=act('media.play',args);case={'mediaId':media,'season':se,'episode':ep,'requestedStreamId':sid,'action':reply}
        decoded=wait(media,None,se,ep,seconds=min(50,remaining-35),before=before);case['beforeManualChoice']=decoded
        active=decoded.get('activeId');selection=decoded.get('selection') or {}
        case['actualFallbackObserved']=decoded.get('passed') is True and active!=sid and selection.get('requestedStreamId')==sid
        if case['actualFallbackObserved']:
            act('player.seek',{'positionMs':30000})
            before=req('streams').get('probeFrames',0);reply=act('player.selectQuality',{'quality':'Auto','persist':False})
            auto=wait(media,active,se,ep,seconds=10,before=before);case['afterAuto']=auto;case['autoAction']=reply
            a=auto.get('selection') or {}
            case['autoPassed']=auto.get('passed') is True and a.get('requestedStreamId')==active and a.get('requestedQuality')=='Auto' and a.get('activeVoice')==selection.get('activeVoice') and auto.get('positionMs',0)>=29000
            height=auto.get('height',0)
            before=req('streams').get('probeFrames',0);reply=act('player.selectQuality',{'quality':str(height)+'p','persist':False})
            exact=wait(media,active,se,ep,seconds=10,before=before);case['afterExistingQuality']=exact;case['qualityAction']=reply
            e=exact.get('selection') or {}
            case['existingQualityPassed']=exact.get('passed') is True and exact.get('height')==height and e.get('requestedStreamId')==active and e.get('requestedQuality')==str(height)+'p' and e.get('activeVoice')==selection.get('activeVoice') and exact.get('positionMs',0)>=29000
            case['passed']=case['autoPassed'] and case['existingQualityPassed']
        else:case['passed']=False
        case['sourceLoadEvidence']=req('diagnostics').get('sourceLoadEvidence')
        out['cases'].append(case)
        if case.get('passed'):break
except Exception as error:out['harnessError']=type(error).__name__
finally:
    act('player.stop');act('player.probeSurface',{'enabled':False});end=req('snapshot')
    out['countsPreserved']=end['library']['counts']==out.get('initialCounts')
    out['passed']=any(x.get('passed') for x in out['cases']) and out['countsPreserved'] and not out.get('harnessError')
    (D/'selection-after-fallback-proof.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
    print(json.dumps({'passed':out['passed'],'cases':[{'mediaId':x['mediaId'],'actualFallbackObserved':x.get('actualFallbackObserved'),'autoPassed':x.get('autoPassed'),'existingQualityPassed':x.get('existingQualityPassed')} for x in out['cases']]}),flush=True)
raise SystemExit(0 if out['passed'] else 1)
