"""Bounded native measurements of concrete MP4 leaves; never a provider label guess."""
from __future__ import annotations
import json, subprocess, tempfile, time, threading, sqlite3, math
from collections import OrderedDict
from contextlib import closing
from pathlib import Path
import requests
from urllib.parse import urljoin
from concurrent.futures import ThreadPoolExecutor
from threading import BoundedSemaphore
from catalog_schema_v2 import normalize_ru_text
from movia_paths import DATA_DIR

_CACHE = OrderedDict()
_LOCK = threading.Lock()
_DIMENSION_WORKERS = ThreadPoolExecutor(max_workers=2,thread_name_prefix='movia-hls-dimensions')
_DIMENSION_SLOTS = BoundedSemaphore(8)
_DIMENSION_ACTIVE = set()
_PLAYLIST_TEXT = OrderedDict()


def catalog_duration_seconds(request, db_path=None):
    # Movie card duration cannot establish a particular episode's duration.
    if request.is_series_request or request.media_type.casefold() != "movie":
        return None
    try:
        path=Path(db_path) if db_path is not None else Path(DATA_DIR)/"catalog.db"
        with closing(sqlite3.connect(path.as_uri()+"?mode=ro",uri=True,timeout=.5)) as conn:
            row=conn.execute("SELECT title,original_title,year,media_type,duration_minutes FROM movies WHERE id=?",(request.media_id,)).fetchone()
        if not row or normalize_ru_text(request.title) not in {normalize_ru_text(row[0]),normalize_ru_text(row[1])}:
            return None
        if request.year and row[2] != request.year:
            return None
        if str(row[3]).casefold() != "movie":
            return None
        duration=float(row[4] or 0)*60
        return duration if duration>0 else None
    except (sqlite3.Error,OSError,ValueError,TypeError):
        return None

def duration_matches(actual, expected):
    try:
        actual=float(actual);expected=float(expected)
        return actual>0 and expected>0 and .7*expected <= actual <= 1.4*expected
    except (TypeError,ValueError):
        return False

def measure_mp4(url, headers):
    """Read at most 512 KiB and inspect that local sample, with hard time bounds.

    A late moov atom or network failure is inconclusive. It is never converted
    into a guessed height, guessed duration or content mismatch.
    """
    key=(url,tuple(sorted((str(k).lower(),str(v)) for k,v in headers.items())))
    with _LOCK:
        cached=_CACHE.get(key)
        if cached and cached[0]>time.monotonic():
            return cached[1]
    result=None
    try:
        with requests.get(url,headers={**headers,"Range":"bytes=0-524287"},stream=True,timeout=(1,1.5)) as response:
            response.raise_for_status()
            chunks=[];size=0;started=time.monotonic()
            for chunk in response.iter_content(65536):
                if time.monotonic()-started>2.5:break
                if not chunk:continue
                chunks.append(chunk[:524288-size]);size+=len(chunks[-1])
                if size>=524288:break
        result=_measure_video_sample(b"".join(chunks))
    except (requests.RequestException,OSError,subprocess.TimeoutExpired,ValueError,TypeError):
        pass
    with _LOCK:
        _CACHE[key]=(time.monotonic()+(600 if result else 20),result)
        _CACHE.move_to_end(key)
        while len(_CACHE)>256:_CACHE.popitem(last=False)
    return result


def hls_duration_from_playlist(text):
    """Only a complete media playlist establishes the concrete item's runtime."""
    if not text.lstrip().startswith('#EXTM3U') or '#EXT-X-ENDLIST' not in text or '#EXT-X-STREAM-INF' in text:
        return None
    durations=[]
    for line in text.splitlines():
        if line.startswith('#EXTINF:'):
            try:seconds=float(line.split(':',1)[1].split(',',1)[0])
            except ValueError:return None
            if not math.isfinite(seconds) or seconds<=0:return None
            durations.append(seconds)
    total=sum(durations)
    return total if durations and math.isfinite(total) else None


def _measure_video_sample(data):
    with tempfile.NamedTemporaryFile(suffix=".mp4") as sample:
        sample.write(data);sample.flush()
        probe=subprocess.run(["ffprobe","-v","error","-show_entries","format=duration:stream=codec_type,width,height","-of","json",sample.name],capture_output=True,text=True,timeout=1.2)
    measured=json.loads(probe.stdout or "{}")
    videos=[x for x in measured.get("streams",[]) if x.get("codec_type")=="video" and int(x.get("height",0))>0]
    seconds=float(measured.get("format",{}).get("duration",0))
    if videos and math.isfinite(seconds) and seconds>0:
        return {"duration":seconds,"height":int(videos[0]["height"]),"width":int(videos[0].get("width",0))}
    return None


def _hls_video_dimensions(text, playlist_url, headers):
    """Inspect a bounded middle segment; no dimensions are inferred from labels."""
    if '#EXT-X-KEY' in text or '#EXT-X-BYTERANGE' in text:
        return None
    segments=[line.strip() for line in text.splitlines() if line.strip() and not line.startswith('#')]
    if not segments:
        return None
    url=urljoin(playlist_url,segments[len(segments)//2])
    if not url.startswith(('https://','http://')):
        return None
    try:
        with requests.get(url,headers={**headers,'Range':'bytes=0-65535'},stream=True,timeout=(.5,.5)) as response:
            response.raise_for_status()
            data=next(response.iter_content(65536),b'')[:65536]
        measured=_measure_video_sample(data)
        if measured:
            return {key:measured[key] for key in ('height','width') if measured.get(key)}
    except (requests.RequestException,OSError,subprocess.TimeoutExpired,ValueError,TypeError):
        pass
    return None


def _schedule_hls_dimensions(text,url,headers,result):
    key=(url,tuple(sorted((str(k).lower(),str(v)) for k,v in headers.items())))
    with _LOCK:
        if key in _DIMENSION_ACTIVE or result.get('height'):
            return
        if not _DIMENSION_SLOTS.acquire(blocking=False):
            return
        _DIMENSION_ACTIVE.add(key)
    slots=_DIMENSION_SLOTS
    def inspect():
        dimensions=_hls_video_dimensions(text,url,headers)
        if dimensions:
            with _LOCK:result.update(dimensions)
    def finished(_):
        with _LOCK:_DIMENSION_ACTIVE.discard(key)
        slots.release()
    try:
        future=_DIMENSION_WORKERS.submit(inspect)
    except Exception:
        finished(None)
        return
    future.add_done_callback(finished)


def measure_hls_content(url,headers):
    """Measure complete HLS or an MP4 substituted for the advertised playlist."""
    key=('hls-content',url,tuple(sorted((str(k).lower(),str(v)) for k,v in headers.items())))
    with _LOCK:
        cached=_CACHE.get(key)
        valid=cached and cached[0]>time.monotonic()
        playlist=_PLAYLIST_TEXT.get(key)
    if valid:
        if cached[1] and playlist:_schedule_hls_dimensions(playlist,url,headers,cached[1])
        return cached[1]
    result=None
    try:
        started=time.monotonic();chunks=[];size=0
        with requests.get(url,headers=headers,stream=True,timeout=(1,1.5)) as response:
            response.raise_for_status()
            for chunk in response.iter_content(65536):
                if time.monotonic()-started>2.5:break
                if chunk:chunks.append(chunk[:524288-size]);size+=len(chunks[-1])
                if size>=524288:break
        data=b''.join(chunks)
        seconds=hls_duration_from_playlist(data.decode('utf-8',errors='replace'))
        if seconds:
            result={'duration':seconds,'container':'hls'}
            text=data.decode('utf-8',errors='replace')
            with _LOCK:
                _PLAYLIST_TEXT[key]=text
                _PLAYLIST_TEXT.move_to_end(key)
                while len(_PLAYLIST_TEXT)>64:_PLAYLIST_TEXT.popitem(last=False)
            _schedule_hls_dimensions(text,url,headers,result)
        elif len(data)>=8 and data[4:8]==b'ftyp':
            measured=_measure_video_sample(data)
            if measured:result={**measured,'container':'mp4'}
    except (requests.RequestException,OSError,subprocess.TimeoutExpired,ValueError,TypeError):pass
    with _LOCK:
        _CACHE[key]=(time.monotonic()+(600 if result else 20),result)
        _CACHE.move_to_end(key)
        while len(_CACHE)>256:_CACHE.popitem(last=False)
    return result


def measure_hls_duration(url,headers):
    result=measure_hls_content(url,headers)
    return result['duration'] if result and result.get('container')=='hls' else None
