"""Bounded search-only observation for the active adapter; no playback or catalog mutation."""
from pathlib import Path
import sqlite3,json,sys,datetime,time,re
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2134'
sys.path.insert(0,str(C/'repo/backend/runtime'))
from provider_contract import ProviderRequest
from hdrezka_provider_adapter import HDRezkaProviderAdapter
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'provider':'HDRezka','searchOnly':True,'cases':[]}
with sqlite3.connect('file:'+str(Path.home()/'projects/media-parser/catalog.db')+'?mode=ro',uri=True) as db:
 rows=db.execute('SELECT id,title,original_title,year,media_type FROM movies WHERE id IN (474,461)').fetchall()
for media,title,original,year,kind in rows:
 start=time.monotonic();c={'mediaId':str(media),'season':1,'episode':2}
 try:
  request=ProviderRequest(media_id=str(media),title=title,year=int(year) if year else None,season=1,episode=2,media_type=kind)
  found,error=HDRezkaProviderAdapter().search(request,aliases=(original,))
  c.update(resultCount=len(found),error=re.sub(r'https?://[^\s]+','[redacted-locator]',str(error)) if error else None)
 except Exception as e:c['exceptionClass']=type(e).__name__
 c['seconds']=round(time.monotonic()-start,2);out['cases'].append(c)
out['limitations']='Two search-only observations; do not establish all 62 discovery errors, article availability, or successful decoder playback.'
(D/'active-provider-search-observation.json').write_text(json.dumps(out,ensure_ascii=False,indent=2));print(json.dumps(out,ensure_ascii=False))
