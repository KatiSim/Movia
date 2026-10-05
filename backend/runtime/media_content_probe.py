"""Bounded native measurements of concrete MP4 leaves; never a provider label guess."""
from __future__ import annotations
import json, subprocess, tempfile, time, threading, sqlite3
from collections import OrderedDict
from contextlib import closing
from pathlib import Path
import requests
from catalog_schema_v2 import normalize_ru_text
from movia_paths import DATA_DIR

_CACHE = OrderedDict()
_LOCK = threading.Lock()

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
            chunks=[];size=0
            for chunk in response.iter_content(65536):
                if not chunk:continue
                chunks.append(chunk[:524288-size]);size+=len(chunks[-1])
                if size>=524288:break
        with tempfile.NamedTemporaryFile(suffix=".mp4") as sample:
            sample.write(b"".join(chunks));sample.flush()
            probe=subprocess.run(["ffprobe","-v","error","-show_entries","format=duration:stream=codec_type,width,height","-of","json",sample.name],
                                 capture_output=True,text=True,timeout=1.2)
        measured=json.loads(probe.stdout or "{}")
        videos=[x for x in measured.get("streams",[]) if x.get("codec_type")=="video" and int(x.get("height",0))>0]
        seconds=float(measured.get("format",{}).get("duration",0))
        if videos and seconds>0:
            result={"duration":seconds,"height":int(videos[0]["height"]),"width":int(videos[0].get("width",0))}
    except (requests.RequestException,OSError,subprocess.TimeoutExpired,ValueError,TypeError):
        pass
    with _LOCK:
        _CACHE[key]=(time.monotonic()+(600 if result else 20),result)
        _CACHE.move_to_end(key)
        while len(_CACHE)>256:_CACHE.popitem(last=False)
    return result
