"""Read-only sample inventory inspection; no player/discovery actions."""
from pathlib import Path
import sys,json,sqlite3,collections,datetime
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2134'
sys.path.insert(0,str(C/'repo/backend/runtime'))
from native_variant_feedback import feedback_fingerprints
ids=[str(x['mediaId']) for x in json.loads((D/'android_coverage_selection.json').read_text())['rows']]
with sqlite3.connect('file:'+str(Path.home()/'projects/media-parser/catalog.db')+'?mode=ro',uri=True) as db:
 rows=db.execute('SELECT id,streams FROM movies WHERE id IN ('+','.join('?' for _ in ids)+')',ids).fetchall()
groups={};count=0;bad=0
for media,raw in rows:
 try:streams=json.loads(raw or '[]')
 except ValueError:bad+=1;continue
 for row in streams:
  if not isinstance(row,dict) or not str(row.get('stream_id') or '').startswith('provider-item:v2:'):continue
  if not str(row.get('url') or row.get('playback_url') or '').strip():continue
  count+=1;fp=feedback_fingerprints(row)
  key=(str(media),row.get('season',row.get('season_number')),row.get('episode',row.get('episode_number')),str(row.get('provider') or row.get('source') or ''),fp['native_feedback_locator_hash'])
  groups.setdefault(key,{}).setdefault(fp['native_feedback_profile_hash'],set()).add(row['stream_id'])
collisions=[{'mediaId':k[0],'season':k[1],'episode':k[2],'provider':k[3],'locatorHash':k[4],'distinctProfiles':len(v),'logicalVariantCount':len(set().union(*v.values()))} for k,v in groups.items() if len(v)>1]
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sampleIds':len(ids),'readCards':len(rows),'nativeLeaves':count,'physicalGroups':len(groups),'parallelProfileGroups':len(collisions),'cardsWithParallelProfiles':len({x['mediaId'] for x in collisions}),'invalidJsonCards':bad,'examples':collisions[:20],'meaning':'Inventory snapshot only. Distinct profile counts are not verified audio/video-track mappings. Zero collisions in this sample does not certify other providers or gated adapters.'}
(D/'parallel-profile-inventory.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(json.dumps(out,ensure_ascii=False))
