from pathlib import Path
import sqlite3,json,random,hashlib,time,collections
out=Path.home()/'.cache/movia-architecture-20261002'
db=Path.home()/'projects/media-parser/catalog.db'
used=set();inputs=[]
def remember(value,key=None):
    if isinstance(value,dict):
        for name,child in value.items():
            if name in {'ids','sampleIds','mediaIds'} and isinstance(child,list):
                for item in child:
                    if str(item).isdigit():used.add(int(item))
            elif name in {'id','mediaId','media_id','catalogMediaId','catalog_media_id'} and str(child).isdigit():
                used.add(int(child))
            elif isinstance(child,(dict,list)):remember(child,name)
    elif isinstance(value,list):
        for child in value:remember(child,key)
for path in sorted(out.glob('*.json')):
    if 'cohort-t-' in path.name or path.stat().st_size>15_000_000:continue
    try:remember(json.loads(path.read_text()));inputs.append(path.name)
    except (ValueError,OSError):pass
# Also exclude the older reproducible unrecorded random cohorts.
for path in [db,out/'catalog-before-identity-cleanup-v4-20261004.db']:
    if not path.exists():continue
    with sqlite3.connect('file:'+str(path)+'?mode=ro',uri=True) as conn:
        old=conn.execute('SELECT id FROM movies ORDER BY id').fetchall()
    for pool in [old,old[:96980]]:
        if len(pool)>=1000:used.update(row[0] for row in random.Random(20261004).sample(pool,1000))
with sqlite3.connect('file:'+str(db)+'?mode=ro',uri=True) as conn:
    conn.row_factory=sqlite3.Row
    candidates=conn.execute("SELECT id,title,original_title,year,media_type,streams FROM movies WHERE media_type='movie' ORDER BY id").fetchall()
eligible=[row for row in candidates if row['id'] not in used]
dest=out/'random1000-cohort-t-ids-20261007.json'
if dest.exists():
    selection=json.loads(dest.read_text());ids=selection['ids']
    index={row['id']:row for row in candidates};sample=[index[ident] for ident in ids]
else:
    sample=random.Random(3441).sample(eligible,1000);ids=[row['id'] for row in sample]
    selection={'seed':3441,'movieOnly':True,'size':1000,'ids':ids,
        'excludedIdsCount':len(used),'excludedEvidenceFiles':inputs,
        'excludedIdsSha256':hashlib.sha256(json.dumps(sorted(used)).encode()).hexdigest(),
        'idsSha256':hashlib.sha256(json.dumps(ids).encode()).hexdigest()}
    dest.write_text(json.dumps(selection,ensure_ascii=False,indent=2))
assert not(set(ids)&used),'Previous audited IDs entered new cohort'
counts=collections.Counter();facts=[]
unknown={'','auto','unknown','не указано','неуказано','n/a','null'}
for row in sample:
    try:streams=json.loads(row['streams'] or '[]')
    except (TypeError,ValueError):streams=[]
    streams=[item for item in streams if isinstance(item,dict)]
    voices=sorted({str(item.get('voice') or '').strip() for item in streams if str(item.get('voice') or '').strip().lower() not in unknown})
    qualities=sorted({str(item.get('quality') or '').strip() for item in streams if str(item.get('quality') or '').strip().lower() not in unknown})
    native=sum(str(item.get('stream_id') or '').startswith('provider-item:v2:') for item in streams)
    record={key:row[key] for key in ['id','title','original_title','year']}
    record.update(foundLeaves=len(streams),nativeConcreteLeaves=native,
        providerVoiceLabels=voices,providerQualityLabels=qualities)
    facts.append(record)
    counts['foundLeaves']+=len(streams);counts['nativeConcreteLeaves']+=native
    counts['cardsWithCandidates']+=bool(streams);counts['cardsWithoutCandidates']+=not streams
result={'at':time.time(),'seed':3441,'size':len(sample),'overlapPreviousIds':0,
    'idsSha256':selection['idsSha256'],'counts':dict(counts),'rows':facts,
    'scope':'Cached candidate baseline only; provider claims are not decoder proof; no fixed voices/qualities target.'}
(out/'cohort-t-baseline-20261007.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({key:value for key,value in result.items() if key!='rows'}))
