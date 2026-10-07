from pathlib import Path
import json,time,uuid,urllib.request,urllib.error
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2134'
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
