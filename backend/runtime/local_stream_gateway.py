"""Local API compatibility around the same bounded discovery service as cloud."""
import re

def stream_gateway_response(movie_id, query, service):
    if not re.fullmatch(r'(?:m_)?[0-9]{1,12}',str(movie_id)):
        return 400, {'status':'ERROR','code':'INVALID_MEDIA_ID'}
    if any(len(query.get(key,[]))>1 for key in ('season','episode','refresh')):
        return 400, {'status':'ERROR','code':'DUPLICATE_ARGUMENT'}
    numbers=[]
    for name,maximum in (('season',1000),('episode',10000)):
        value=query.get(name,[None])[0]
        if value is not None and (not value.isdecimal() or not 1<=int(value)<=maximum):
            return 400, {'status':'ERROR','code':'INVALID_'+name.upper()}
        numbers.append(int(value) if value is not None else None)
    refresh=query.get('refresh',['0'])[0]
    if refresh not in {'0','1'}:
        return 400, {'status':'ERROR','code':'INVALID_REFRESH'}
    code,body=service(str(movie_id),*numbers,force_refresh=refresh=='1')
    body=dict(body)
    rows=body.get('streams') or []
    if code==200:
        body.update(id=body.get('mediaId'),playback_url=rows[0]['url'] if rows else '',top_stream=rows[0] if rows else None)
    return code,body
