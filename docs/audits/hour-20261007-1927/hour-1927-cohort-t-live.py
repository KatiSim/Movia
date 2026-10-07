from pathlib import Path
import json,time,datetime,urllib.request,collections,concurrent.futures,signal,threading,os
out=Path.home()/'.cache/movia-architecture-20261002'
selection=json.loads((out/'random1000-cohort-t-ids-20261007.json').read_text())
journal=out/'cohort-t-live-results-20261007.jsonl'
deadline=datetime.datetime(2026,10,7,20,21,tzinfo=datetime.timezone.utc).timestamp()
stop=threading.Event()
signal.signal(signal.SIGTERM,lambda *args:stop.set())
signal.signal(signal.SIGINT,lambda *args:stop.set())
def request(ident,refresh=False):
    url='http://127.0.0.1:8888/api/movie/'+str(ident)+'/stream'+('?refresh=1' if refresh else '')
    with urllib.request.urlopen(url,timeout=18) as response:
        return json.load(response)
def summary(ident,body):
    rows=[row for row in body.get('streams',[]) if isinstance(row,dict)]
    native=[row for row in rows if str(row.get('stream_id') or '').startswith('provider-item:v2:')]
    decoded=[row for row in rows if (row.get('sourceTruth') or {}).get('decodedPlayback') is True]
    manifests=[row for row in rows if (row.get('transport_metadata') or {}).get('manifest_verified') is True]
    wrong=[row.get('stream_id') for row in rows if str(row.get('catalog_media_id') or '')!=str(ident)]
    return {'mediaId':str(ident),'responseMediaId':str(body.get('mediaId')),'status':body.get('status'),
        'discoveryStatus':body.get('discoveryStatus'),'foundLeaves':len(rows),'nativeConcreteLeaves':len(native),
        'decodedLeaves':len(decoded),'manifestInspectedLeaves':len(manifests),'identityMismatches':wrong,
        'providers':dict(collections.Counter(str(row.get('provider') or row.get('source') or 'unknown') for row in native)),
        'voiceLabels':sorted({str(row.get('voice') or 'Не указано') for row in rows}),
        'qualityLabels':sorted({str(row.get('quality') or 'Не указано') for row in rows}),
        'streamIds':[str(row.get('stream_id')) for row in native],
        'sourceIds':[str(row.get('source_id') or row.get('sourceId') or '') for row in native]}
def run(ident):
    started=time.monotonic()
    result={'mediaId':str(ident),'at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        body=request(ident,True);result['firstDiscoveryStatus']=body.get('discoveryStatus')
        seen_active=body.get('discoveryStatus') in {'RUNNING','QUEUED'}
        terminal=False
        while not stop.is_set() and time.time()<deadline and time.monotonic()-started<75:
            state=body.get('discoveryStatus')
            if state in {'READY','UNAVAILABLE','STOPPED'}:
                terminal=True;break
            if state=='IDLE' and seen_active:
                result['terminalStatusExpired']=True;terminal=True;break
            if body.get('mediaId') is None:break
            time.sleep(1)
            body=request(ident)
            seen_active=seen_active or body.get('discoveryStatus') in {'RUNNING','QUEUED'}
        result.update(summary(ident,body))
        result['observedActiveDiscovery']=seen_active
        result['terminalObserved']=terminal
        result['outcome']='completed_with_candidates' if terminal and result['nativeConcreteLeaves'] else ('provider_unavailable' if terminal and result['discoveryStatus']=='UNAVAILABLE' else 'pending_or_unconfirmed')
        if result['firstDiscoveryStatus']=='UNAVAILABLE' and not seen_active:result['outcome']='provider_cooldown'
        if result['identityMismatches'] or result['responseMediaId']!=str(ident):result['outcome']='identity_error'
    except Exception as error:
        result['outcome']='request_error';result['errorType']=type(error).__name__
    result['seconds']=round(time.monotonic()-started,3)
    return result
records=[]
if journal.exists():
    for line in journal.read_text().splitlines():
        try:records.append(json.loads(line))
        except ValueError:pass
done={record['mediaId'] for record in records}
todo=iter(ident for ident in selection['ids'] if str(ident) not in done)
def progress(final=False):
    counts=collections.Counter(record['outcome'] for record in records)
    body={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'cohortSize':1000,
        'idsSha256':selection['idsSha256'],'attempted':len(records),'unattempted':1000-len(records),
        'outcomes':dict(counts),'foundLeaves':sum(record.get('foundLeaves',0) for record in records),
        'nativeConcreteLeaves':sum(record.get('nativeConcreteLeaves',0) for record in records),
        'decodedLeaves':sum(record.get('decodedLeaves',0) for record in records),
        'identityErrors':counts.get('identity_error',0),'workers':2,'finalForThisRun':final,
        'completeCohort':len(records)==1000,'noPlaybackClaim':True}
    target=out/'cohort-t-live-progress-20261007.json';tmp=target.with_suffix('.tmp')
    tmp.write_text(json.dumps(body,ensure_ascii=False,indent=2));tmp.replace(target)
    print(json.dumps(body,ensure_ascii=False),flush=True)
progress()
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    pending={}
    def submit():
        if stop.is_set() or time.time()>=deadline:return False
        ident=next(todo,None)
        if ident is None:return False
        pending[pool.submit(run,ident)]=ident
        return True
    submit();submit()
    while pending:
        completed,_=concurrent.futures.wait(pending,timeout=5,return_when=concurrent.futures.FIRST_COMPLETED)
        for future in completed:
            pending.pop(future);record=future.result();records.append(record)
            with journal.open('a') as file:file.write(json.dumps(record,ensure_ascii=False)+'\n');file.flush();os.fsync(file.fileno())
            if len(records)%25==0:progress()
            submit()
        if stop.is_set() or time.time()>=deadline:progress()
progress(True)
