"""Download and decode one real short catalog film using the native app pipeline."""
from pathlib import Path
import urllib.request,urllib.error,json,time,uuid,datetime,re
ROOT=Path(__file__).resolve().parent
TOKEN=(Path.home()/'.config/movia-agent/token').read_text().strip()
def req(path,body=None,port=8899):
    q=urllib.request.Request('http://127.0.0.1:'+str(port)+('/agent/v1/' if port==8899 else '/')+path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(q,timeout=15) as r:
            b=r.read()
            return json.loads(b) if b and b[:1] in (b'{',b'[') else {}
    except urllib.error.HTTPError as error:
        body=error.read().decode('utf-8','replace')
        body=re.sub(r'https?://[^\s\"<>]+','[redacted-locator]',body)
        raise RuntimeError('HTTP_'+str(error.code)+': '+body[:350]) from None
def act(name,args=None):
    return req('action',{'action':name,'arguments':args or {},'requestId':'hour-offline-'+uuid.uuid4().hex})
def wait(ident,seconds=45):
    start=time.monotonic();frames=req('streams').get('probeFrames',0);last={}
    while time.monotonic()-start<seconds:
        d=req('diagnostics');s=req('streams');m=d.get('media3',{});p=d.get('snapshot',{}).get('playback',{})
        last={'seconds':round(time.monotonic()-start,2),'height':m.get('videoHeight'),'state':m.get('switchState'),
            'playing':m.get('isPlaying'),'mediaItemId':str(m.get('mediaItemId')),'activeId':s.get('activeStreamId'),
            'framesBefore':frames,'framesAfter':s.get('probeFrames',0),'durationMs':p.get('durationMs'),
            'selection':d.get('streamSelection'),'audioTracks':[{'id':a.get('id'),'language':a.get('language'),'selected':a.get('selected')} for a in s.get('audioTracks',[])]}
        if m.get('isPlaying') is True and m.get('switchState')=='READY' and str(m.get('mediaItemId'))==ident and s.get('probeFrames',0)>frames+3:
            last['passed']=True;return last
        time.sleep(.5)
    last['passed']=False;return last
result={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mediaId':'26041','title':'О птичках','year':2000,'realCatalogContent':True,'requestWifiOnly':False,'schedulingBudgetSeconds':120}
try:
    snap=req('snapshot');assert snap['app']['uiAttached'] is False
    assert 'О птичках' not in snap['downloads']['titles'],'Existing user download; do not overwrite'
    act('player.probeSurface',{'enabled':True})
    body=req('api/movie/26041/stream',port=8888)
    sources=[s for s in body.get('streams',[]) if str(s.get('stream_id') or '').startswith('provider-item:v2:') and
        str(s.get('catalog_media_id') or '')=='26041' and not str(s.get('url') or '').startswith('magnet:')]
    assert sources,'No real native direct source'
    source=min(sources,key=lambda s:(not (s.get('sourceTruth') or {}).get('decodedPlayback',False),str(s.get('stream_id'))))
    result['selectedStreamId']=source['stream_id']
    act('media.play',{'mediaId':'26041','streamId':source['stream_id'],'quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False})
    result['online']=wait('26041',55)
    assert result['online']['passed'] and result['online']['activeId']==source['stream_id'],'Pinned short film failed'
    reply=act('downloads.enqueue',{'mediaId':'26041','title':'О птичках','wifiOnly':False})
    result['enqueueStatus']=reply.get('status');result['testOwnedDownload']=True
    deadline=time.monotonic()+120;states=[]
    while time.monotonic()<deadline:
        status=act('downloads.status',{'title':'О птичках','mediaId':'26041'})
        # Completed action responses expose their result at the top level.
        if not states or status!=states[-1]:states.append(status)
        raw=json.dumps(status)
        if 'SUCCEEDED' in raw or 'FAILED' in raw or 'CANCELLED' in raw:break
        time.sleep(2)
    result['downloadStatus']=status
    result['downloadCompleted']='SUCCEEDED' in json.dumps(status)
    result['downloadStateChanges']=states[-10:]
    assert result['downloadCompleted'],'Real film download did not complete'
    act('player.stop')
    act('media.play',{'mediaId':'26041','persist':False,'recordHistory':False,'resume':False})
    result['offline']=wait('26041',25)
    assert result['offline']['passed'],'Offline film did not decode'
    source_name=str((result['offline'].get('selection') or {}).get('source') or '').lower()
    result['offlineSourceConfirmed']=source_name=='offline' or str(result['offline'].get('activeId') or '').startswith('offline')
    assert result['offlineSourceConfirmed'],'A network fallback is not an offline pass'
    before=req('streams').get('probeFrames',0);act('player.seek',{'positionMs':30000})
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        d=req('diagnostics');s=req('streams');m=d.get('media3',{})
        if m.get('isPlaying') and 29000<=m.get('currentPositionMs',0)<60000 and s.get('probeFrames',0)>before+3:break
        time.sleep(.5)
    result['offlineSeek']={'passed':bool(m.get('isPlaying')) and 29000<=m.get('currentPositionMs',0)<60000 and s.get('probeFrames',0)>before+3,'positionMs':m.get('currentPositionMs'),'framesAfter':s.get('probeFrames')}
    result['passed']=result['offlineSeek']['passed']
except Exception as error:
    result['error']=type(error).__name__+': '+str(error)[:140]
    result['passed']=False
finally:
    try:act('player.stop');act('player.probeSurface',{'enabled':False});result['playerCleanup']=True
    except Exception:result['playerCleanup']=False
    if result.get('testOwnedDownload'):
        try:
            act('downloads.delete',{'title':'О птичках','mediaId':'26041'});result['testDownloadCleanup']=True
        except Exception:result['testDownloadCleanup']=False
    result['installedApkSha256']=json.loads((Path.home()/'.cache/movia-architecture-20261002/hour-2034-offline-install-cold.json').read_text())['installedSha256']
    (ROOT/'offline_smoke.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print(json.dumps(result,ensure_ascii=False),flush=True)
raise SystemExit(0 if result.get('passed') else 1)
