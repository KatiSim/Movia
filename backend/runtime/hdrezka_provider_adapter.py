#!/usr/bin/env python3
"""Movia-native HDRezka ProviderContract adapter.

Reimplements verified provider behavior: exact search result -> article ->
translator/episode branches -> concrete stream leaves. Existing Movia transport
helpers are reused only for HTTP/provider decoding; identity, tree composition,
logging and candidate metadata are owned by Movia.
"""
from __future__ import annotations
import logging,re
from threading import BoundedSemaphore
from concurrent.futures import ThreadPoolExecutor,wait
from media_content_probe import catalog_duration_seconds,duration_matches,measure_mp4,measure_hls_content
from typing import Any,Dict,List,Optional,Tuple
from urllib.parse import quote,urlparse
import requests
from bs4 import BeautifulSoup
from catalog_schema_v2 import normalize_ru_text
from provider_contract import ProviderArticle,ProviderDefinition,ProviderRequest,ProviderRequestProfile,ProviderSearchResult,VariantFolder,VariantStream
from zona_legacy_adapters import _resolve_hdrezka,HDREZKA_DEFAULT_USER_AGENT

logger=logging.getLogger('hdrezka_provider_adapter')
_CONTENT_PROBES=ThreadPoolExecutor(max_workers=8,thread_name_prefix='movia-content-probe')
# Bound running + queued measurements across concurrent articles. Inventory is
# retained when probe capacity is exhausted; unknown metadata stays unknown.
_CONTENT_PROBE_SLOTS=BoundedSemaphore(24)

def _submit_content_probe(measure,url,headers):
    if not _CONTENT_PROBE_SLOTS.acquire(blocking=False):
        return None
    slots=_CONTENT_PROBE_SLOTS
    try:
        future=_CONTENT_PROBES.submit(measure,url,headers)
    except Exception:
        slots.release()
        raise
    future.add_done_callback(lambda _:slots.release())
    return future
BASE='https://rezka.ag'
DEF=ProviderDefinition(provider_id='movia:hdrezka',name='HDRezka',family='movia-rewrite',capabilities=frozenset({'movie','series','exact-identity','voice','quality'}),request_profile=ProviderRequestProfile(user_agent=HDREZKA_DEFAULT_USER_AGENT,base_urls=(BASE,),properties={'architecture':'provider-contract-variant-tree'}))


def _get(url:str,headers:Dict[str,str])->Tuple[Optional[str],Optional[str]]:
    try:
        r=requests.get(url,headers=headers,timeout=5)
        return (r.text,None) if r.status_code==200 else (None,f'HTTP_ERROR:{r.status_code}')
    except Exception as e:return None,type(e).__name__

def _get_headers(url:str,headers:Dict[str,str]):
    try:
        r=requests.get(url,headers=headers,timeout=5)
        hs={k:v for k,v in r.headers.items()}
        return ((r.text,hs,None) if r.status_code==200 else (None,hs,f'HTTP_ERROR:{r.status_code}'))
    except Exception as e:return None,{},type(e).__name__

def _post(url:str,headers:Dict[str,str],form:Dict[str,str]):
    try:
        r=requests.post(url,headers=headers,data=form,timeout=5)
        return (r.text,None) if r.status_code==200 else (None,f'HTTP_ERROR:{r.status_code}')
    except Exception as e:return None,type(e).__name__


def search_exact(title:str,year:Optional[int],accepted_titles=())->Tuple[List[ProviderSearchResult],Optional[str]]:
    q=title+(f' {year}' if year else '')
    body,error=_get(f'{BASE}/search/?do=search&subaction=search&q={quote(q)}',{'User-Agent':HDREZKA_DEFAULT_USER_AGENT})
    if error or not body:return [],error or 'EMPTY_SEARCH'
    soup=BeautifulSoup(body,'lxml'); out=[]
    for item in soup.select('.b-content__inline_item'):
        link=item.select_one('.b-content__inline_item-link a[href]')
        meta=item.select_one('.b-content__inline_item-link div')
        if not link:continue
        name=' '.join(link.get_text(' ',strip=True).split())
        href=str(link.get('href') or '').strip()
        m=re.search(r'\b(19\d{2}|20\d{2})\b',meta.get_text(' ',strip=True) if meta else '')
        item_year=int(m.group(1)) if m else None
        accepted_norm={normalize_ru_text(x) for x in (accepted_titles or (title,)) if str(x or '').strip()}
        provider_title_variants={normalize_ru_text(part) for part in re.split(r'\s*/\s*',name) if str(part or '').strip()}
        if not (provider_title_variants & accepted_norm):continue
        if year and int(year)!=item_year:continue
        parsed=urlparse(href); path=parsed.path.strip('/')
        if path.endswith('.html'):path=path[:-5]
        if not path:continue
        out.append(ProviderSearchResult(DEF,path,name,item_year,href,path))
    # exact search must never guess between multiple article identities
    uniq={x.item_id:x for x in out}
    return list(uniq.values()),None


class HDRezkaProviderAdapter:
    definition=DEF
    def __init__(self,measure=measure_mp4,expected_duration=catalog_duration_seconds,measure_playlist=measure_hls_content):
        self.measure=measure
        self.expected_duration=expected_duration
        self.measure_playlist=measure_playlist
    def search(self,request:ProviderRequest,aliases=()):
        accepted=[]
        for value in (request.title,*tuple(aliases or ())):
            text=str(value or '').strip()
            if text and normalize_ru_text(text) not in {normalize_ru_text(x) for x in accepted}: accepted.append(text)
        found={}
        errors=[]
        accepted_norm={normalize_ru_text(x) for x in accepted}
        for query_title in accepted:
            rows,error=search_exact(query_title,request.year,accepted_titles=accepted)
            if error: errors.append(error)
            for row in rows:
                row_variants={normalize_ru_text(part) for part in re.split(r'\s*/\s*',row.title) if str(part or '').strip()}
                is_series_article=row.item_id.startswith("series/")
                expects_series=request.media_type.casefold() in {"tv","series","serial","tv_series"} or request.is_series_request
                if row_variants & accepted_norm and is_series_article == expects_series:
                    found[row.item_id]=row
        error = errors[0] if errors and not found else None
        rows=list(found.values())
        logger.info('HDRezka exact search media_id=%s aliases=%s matches=%s error=%s',request.media_id,len(accepted),len(rows),error)
        return rows,error
    def resolve_source(self,source:ProviderSearchResult,request:ProviderRequest):
        streams,error=_resolve_hdrezka({'downloadLinkKey':source.item_id},fetch_text=_get,fetch_text_with_headers=_get_headers,fetch_post_form_text=_post,request_user_agent=HDREZKA_DEFAULT_USER_AGENT,season=request.season,episode=request.episode)
        if not streams:
            logger.info('HDRezka VariantTree no result media_id=%s error=%s',request.media_id,error)
            return None,None,error or 'HDREZKA_NO_RESULTS'
        article=ProviderArticle(DEF,source.item_id,source.title,source.year,source.article_ref,source.content_ref)
        groups:Dict[str,List[VariantStream]]={}
        expected=self.expected_duration(request)
        probes={};playlist_urls=set()
        if expected or request.is_series_request:
            for row in streams:
                url=str(row.get('url') or '').strip()
                path=urlparse(url).path.lower()
                measure=None
                if path.endswith('.mp4'):measure=self.measure
                elif request.is_series_request and path.endswith('.m3u8') and (row.get('transport_metadata') or {}).get('hdrezka_episode_verified') is True:
                    measure=self.measure_playlist;playlist_urls.add(url)
                if measure is not None and url not in probes:
                    future=_submit_content_probe(measure,url,dict(row.get('headers') or {}))
                    if future is not None:probes[url]=future
            done,pending=wait(list(probes.values()),timeout=2.5) if probes else (set(),set())
            for future in pending:future.cancel()
        else:done=set()
        measurements={}
        for url,future in probes.items():
            if future not in done:continue
            try:value=future.result()
            except Exception:continue
            if isinstance(value,(float,int)) and not isinstance(value,bool):value={'duration':value,'container':'hls'}
            if isinstance(value,dict):measurements[url]=value
        # Independent qualities of a complete media playlist must agree before
        # its runtime is applied to the other representations of this episode.
        verified_playlists=set((url,str(row.get('quality')),measurements[url]['duration'])
            for row in streams for url in [str(row.get('url') or '').strip()]
            if url in playlist_urls and measurements.get(url,{}).get('container')=='hls')
        episode_runtime=None
        runtime_clusters=[]
        for url,quality,duration in verified_playlists:
            group=[(u,q,d) for u,q,d in verified_playlists if abs(d-duration)<=max(1,duration*.02)]
            if len({q for u,q,d in group})>=2 and len({u for u,q,d in group})>=2:
                runtime_clusters.append(group)
        if runtime_clusters:
            score=max(len({q for u,q,d in g}) for g in runtime_clusters)
            best=[g for g in runtime_clusters if len({q for u,q,d in g})==score]
            runtimes=[sum(d for u,q,d in g)/len(g) for g in best]
            # Equally supported conflicting runtimes remain unknown.
            if max(runtimes)-min(runtimes)<=max(1,min(runtimes)*.02):episode_runtime=runtimes[0]
        if request.is_series_request and episode_runtime is None:
            logger.info('HDRezka exact episode content unverified media_id=%s season=%s episode=%s',request.media_id,request.season,request.episode)
            return None,None,'HDREZKA_EPISODE_CONTENT_UNVERIFIED'
        if not expected and episode_runtime:expected=episode_runtime
        for i,row in enumerate(streams):
            url=str(row.get('url') or '').strip()
            if not url:continue
            if request.is_series_request and (row.get('season'),row.get('episode')) != (request.season,request.episode):
                continue
            if request.is_series_request and (row.get('transport_metadata') or {}).get('hdrezka_episode_verified') is not True:
                continue
            voice=str(row.get('voice') or row.get('translation') or 'Не указано').strip() or 'Не указано'
            quality=str(row.get('quality') or 'Не указано').strip() or 'Не указано'
            measured=measurements.get(url)
            if measured and expected and not duration_matches(measured.get('duration'),expected):
                logger.warning('HDRezka content duration mismatch media_id=%s actual=%s expected=%s',request.media_id,measured.get('duration'),expected)
                continue
            if measured and measured.get('height'):
                quality=str(measured['height'])+'p'
            key=f'{source.item_id}|{voice.casefold()}|{quality.casefold()}|{i}'
            leaf=VariantStream(url=url,stream_key=key,voice=voice,quality=quality,season=request.season,episode=request.episode,headers=dict(row.get('headers') or {}),user_agent=str(row.get('user_agent') or HDREZKA_DEFAULT_USER_AGENT),subtitles=tuple(dict(x) for x in (row.get('subtitle_list') or row.get('subtitles') or []) if isinstance(x,dict)),audio_track_index=row.get('audio_track_index'),transport=str(row.get('transport') or ('hls' if '.m3u8' in url else 'direct')),reload_supported=True,transport_metadata={**dict(row.get('transport_metadata') or {}),**({'expected_episode_duration_ms':int(episode_runtime*1000)} if episode_runtime else {}),**({"measured_duration_ms":int(measured["duration"]*1000),**({"measured_height":measured['height']} if measured.get('height') else {})} if measured else {})})
            groups.setdefault(voice,[]).append(leaf)
        voices=tuple(VariantFolder(voice=v,season=request.season,episode=request.episode,children=tuple(ls)) for v,ls in groups.items() if ls)
        if not voices:return None,None,'HDREZKA_NO_PLAYABLE_LEAVES'
        root=VariantFolder(children=(VariantFolder(season=request.season,children=(VariantFolder(season=request.season,episode=request.episode,children=voices),)),)) if request.is_series_request else VariantFolder(children=voices)
        logger.info('HDRezka VariantTree resolved media_id=%s voices=%s leaves=%s',request.media_id,len(voices),sum(len(x.children) for x in voices))
        return root,article,None
