from pathlib import Path
import json,time,uuid,urllib.request,urllib.error
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2034'
token=(Path.home()/'.config/movia-agent/token').read_text().strip()
def req(path,body=None,port=8899):
 q=urllib.request.Request('http://127.0.0.1:'+str(port)+('/agent/v1/' if port==8899 else '/')+path,
 data=json.dumps(body).encode() if body is not None else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(q,timeout=15 if port==8888 else 8) as r:return json.loads(r.read() or '{}')
 except urllib.error.HTTPError as e:
  b=json.loads(e.read() or '{}')
  return {'httpStatus':e.code,'code':b.get('code'),'status':b.get('status'),'error':b.get('error') if isinstance(b.get('error'),str) and len(b['error'])<70 else None}
def act(name,args=None):
 result=req('action',{'action':name,'arguments':args or {},'requestId':'systemic-final-'+uuid.uuid4().hex})
 return {k:result.get(k) for k in ['httpStatus','code','status','error','operationId']}
def rows(x):
 if isinstance(x,dict):return ([x] if 'streamId' in x and 'quality' in x else [])+sum([rows(v) for v in x.values()],[])
 if isinstance(x,list):return sum([rows(v) for v in x],[])
 return []
def wait(media,sid,se=None,ep=None,seconds=30,before=0):
 t=time.monotonic();last={}
 while time.monotonic()-t<seconds:
  d=req('diagnostics');s=req('streams');m=d.get('media3',{});p=d.get('snapshot',{}).get('playback',{})
  selected=next((x for x in rows(s) if x.get('streamId')==s.get('activeStreamId')),{})
  last={'seconds':round(time.monotonic()-t,2),'mediaId':str(m.get('mediaItemId')),'season':p.get('season'),'episode':p.get('episode'),
    'state':m.get('switchState'),'playing':m.get('isPlaying'),'height':m.get('videoHeight'),'positionMs':m.get('currentPositionMs'),
    'frames':s.get('probeFrames'),'activeId':s.get('activeStreamId'),'selection':d.get('streamSelection'),
    'candidateQuality':selected.get('quality'),'sourceIdPresent':bool(selected.get('sourceId')),
    'localDecoded':selected.get('transportMetadata',{}).get('playback_decoded'),
    'referenceRuntimeLoaded':d.get('legacyEngine',{}).get('referenceRuntimeLoaded')}
  if m.get('isPlaying') and m.get('switchState')=='READY' and s.get('probeFrames',0)>before+4 and m.get('videoHeight',0)>0:
   last['passed']=last['mediaId']==media and (sid is None or last['activeId']==sid) and (se is None or (last['season'],last['episode'])==(se,ep))
   return last
  if m.get('switchState')=='FAILED':break
  time.sleep(.4)
 last['passed']=False;return last
def backend_evidence(media,sid,se=None,ep=None):
 path='api/movie/'+media+'/stream'+('?season='+str(se)+'&episode='+str(ep) if se is not None else '')
 b=req(path,port=8888)
 row=next((x for x in b.get('streams',[]) if x.get('stream_id')==sid),{})
 truth=row.get('sourceTruth') or {}
 return {'status':b.get('status'),'sourceIdPresent':bool(row.get('sourceId')),'quality':row.get('quality'),
   'verificationStatus':truth.get('verificationStatus'),'verificationMethod':truth.get('verificationMethod'),
   'decoded':truth.get('decodedPlayback'),'actualQuality':truth.get('actualQuality'),'sourceId':truth.get('sourceId')}
out={'at':time.time(),'cases':[],'installedApkSha256':json.loads((C/'hour-2034-offline-install-cold.json').read_text()).get('builtSha256')}
try:
 snapshot=req('snapshot');assert snapshot['app']['uiAttached'] is False
 out['initialCounts']=snapshot['library']['counts']
 act('player.probeSurface',{'enabled':True})
 high='provider-item:v2:016cafe2eca821dc1ef9128f';low='provider-item:v2:a03531516b353e38601ba3eb'
 for sid,name in [(high,'episode480'),(low,'episode240')]:
  act('player.stop');before=req('streams').get('probeFrames',0);started=time.monotonic()
  response=act('media.play',{'mediaId':'159','season':1,'episode':2,'streamId':sid,'quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False})
  case=wait('159',sid,1,2,before=before);case.update(case=name,action=response,requestToDecodedSeconds=round(time.monotonic()-started,2))
  time.sleep(1)
  case['backendEvidence']=backend_evidence('159',sid,1,2)
  out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
 # Force a second inventory publication; it must keep already decoded dimensions.
 req('api/movie/159/stream?season=1&episode=2&refresh=1',port=8888)
 time.sleep(5)
 act('player.seek',{'positionMs':30000})
 before=req('streams').get('probeFrames',0)
 response=act('player.selectQuality',{'quality':'480p','persist':False})
 case=wait('159',high,1,2,before=before);case.update(case='quality480AfterRediscoveryAndSeek',action=response)
 case['positionPreserved']=case.get('positionMs',0)>=29000
 out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
 before=req('streams').get('probeFrames',0)
 response=act('player.selectQuality',{'quality':'240p','persist':False})
 case=wait('159',low,1,2,before=before);case.update(case='quality240Return',action=response)
 out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
 for media in ['26041','645','1168']:
  act('player.stop')
  body=req('api/movie/'+media+'/stream',port=8888)
  candidates=[x for x in body.get('streams',[]) if str(x.get('stream_id') or '').startswith('provider-item:v2:') and str(x.get('url') or '').startswith('http')]
  if not candidates:out['cases'].append({'case':'movie'+media,'passed':False,'reason':'NO_NATIVE'});continue
  if media=='26041':
   sid='provider-item:v2:045fb4de1875f54020947bf8:8ea4e1f6fd8787bb7bab5a2e'
  else:
   candidate=min(candidates,key=lambda x:(not bool((x.get('transport_metadata') or {}).get('measured_height')),x.get('voice') not in ['Дубляж','HDrezka Studio'],x.get('stream_id')))
   sid=candidate['stream_id']
  evidence_before=backend_evidence(media,sid);before=req('streams').get('probeFrames',0);started=time.monotonic()
  response=act('media.play',{'mediaId':media,'streamId':sid,'quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False})
  case=wait(media,sid,before=before);case.update(case='movie'+media,action=response,requestToDecodedSeconds=round(time.monotonic()-started,2),backendBefore=evidence_before)
  time.sleep(1);case['backendAfter']=backend_evidence(media,sid)
  out['cases'].append(case);print(json.dumps(case,ensure_ascii=False),flush=True)
finally:
 act('player.stop');act('player.probeSurface',{'enabled':False})
 snapshot=req('snapshot');out['finalCounts']=snapshot['library']['counts'];out['finalPlayback']=snapshot['playback']
 out['passed']=all(x.get('passed') for x in out['cases'])
 (D/'systemic-final-runtime.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
 print(json.dumps({'allRuntimeChecksPassed':out['passed'],'caseCount':len(out['cases']),'countsPreserved':out['initialCounts']==out['finalCounts']}),flush=True)
