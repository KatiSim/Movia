"""Movia's public read API. Provider work runs in the worker, never in a GET handler."""
import hashlib
import ipaddress
import json
import re
import threading
import time
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlsplit

class InvalidRequest(ValueError):
    pass

def public_locator(raw):
    try:
        if not isinstance(raw, str) or len(raw) > 8192 or any(c in raw for c in "\r\n\0"):
            return False
        url = urlsplit(raw)
        host = (url.hostname or "").lower()
        if url.scheme not in {"http", "https"} or not host or url.username or url.password:
            return False
        if host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
            return False
        try: return ipaddress.ip_address(host).is_global
        except ValueError: return "." in host and not host.isdecimal()
    except ValueError:
        return False

def public_payload(value):
    if isinstance(value, list): return [public_payload(item) for item in value]
    if not isinstance(value, dict): return value
    result = {key: public_payload(item) for key, item in value.items() if key != "streams"}
    if "streams" in value:
        result["streams"] = [public_payload(item) for item in value["streams"] if isinstance(item, dict)
                            and public_locator(item.get("url") or item.get("playback_url"))]
    for key in ("playback_url", "playbackUrl"):
        if key in result and not public_locator(result[key]): result[key] = ""
    if isinstance(result.get("headers"), dict):
        result["headers"] = {key: item for key, item in result["headers"].items()
                             if key.lower() not in {"authorization", "proxy-authorization", "host"}}
    return result

def parse_request(target):
    if len(target) > 2048:
        raise InvalidRequest("request_too_long")
    parsed = urlsplit(target)
    if parsed.scheme or parsed.netloc or parsed.fragment:
        raise InvalidRequest("invalid_target")
    query = parse_qs(parsed.query, keep_blank_values=True, max_num_fields=20)
    if any(len(values) != 1 or len(values[0]) > 256 for values in query.values()):
        raise InvalidRequest("invalid_query")
    return parsed.path, query

def positive(query, name, default, maximum):
    value = query.get(name, [str(default)])[0]
    if not value.isdecimal() or not 1 <= int(value) <= maximum:
        raise InvalidRequest("invalid_" + name)
    return int(value)

class ReadService:
    def __init__(self, catalog, streams):
        self.catalog, self.streams = catalog, streams
        self.cache = OrderedDict()
        self.lock = threading.Lock()

    def dispatch(self, target):
        path, query = parse_request(target)
        if path == "/health":
            return 200, {"status": "ok", "service": "movia-api", "parserRunsOnPhone": False}
        if path == "/api/providers/config":
            if query: raise InvalidRequest("provider_configuration_query")
            from provider_configuration import configuration
            return 200, configuration()
        if path == "/api/home":
            return 200, self.catalog.get_home_payload()
        if path == "/api/genres":
            return 200, self.catalog.get_all_genres()
        if path == "/api/catalog":
            from catalog_query_contract import parse_catalog_query
            return 200, self.catalog.get_catalog_paged(**parse_catalog_query(query))
        if path in {"/api/search", "/api/movie/search"}:
            result = self.catalog.search_catalog(query.get("query", query.get("q", [""]))[0],
                limit=positive(query, "limit", 20, 100), discover=False)
            return 200, result.get("movies", []) if path.endswith("/movie/search") else result
        if path == "/api/person":
            name = query.get("name", [""])[0].strip()
            scope = query.get("credit_scope", ["all"])[0]
            if not name or scope not in {"all", "actor", "director", "creator"}:
                raise InvalidRequest("invalid_person")
            return 200, self.catalog.get_person_projects(name, limit=positive(query, "limit", 100, 200),
                credit_scope=scope, enrich=False)
        match = re.fullmatch(r"/api/movie/(m_)?([0-9]{1,12})(/stream)?", path)
        if match:
            movie_id = (match[1] or "") + match[2]
            if match[3]:
                season = positive(query, "season", 1, 1000) if "season" in query else None
                episode = positive(query, "episode", 1, 10000) if "episode" in query else None
                if (season is None) != (episode is None):
                    raise InvalidRequest("exact_episode_required")
                return self.streams(movie_id, season, episode)
            movie = self.catalog.get_movie_details(movie_id, enrich=False)
            return (200, movie) if movie else (404, {"code": "NOT_FOUND"})
        return 404, {"code": "NOT_FOUND"}

    def response(self, target):
        # Cache bounded responses, not signed media bytes; unknown routes and invalid queries never enter the cache.
        now = time.monotonic()
        with self.lock:
            hit = self.cache.get(target)
            if hit and now - hit[0] < 15:
                self.cache.move_to_end(target)
                return hit[1:]
        try:
            status, value = self.dispatch(target)
        except ValueError:
            status, value = 400, {"code": "INVALID_ARGUMENT"}
        body = json.dumps(public_payload(value), ensure_ascii=False, separators=(",", ":")).encode()
        if len(body) > 2 * 1024 * 1024:
            return 503, b'{"code":"RESPONSE_TOO_LARGE"}', ""
        etag = '"' + hashlib.sha256(body).hexdigest() + '"'
        if status == 200 and target != "/health":
            with self.lock:
                self.cache[target] = (now, status, body, etag)
                while len(self.cache) > 64:
                    self.cache.popitem(last=False)
        return status, body, etag

def handler(service):
    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self): self.do_GET(send_body=False)
        def do_GET(self, send_body=True):
            try:
                status, body, etag = service.response(self.path)
            except Exception:
                status, body, etag = 503, b'{"code":"TEMPORARILY_UNAVAILABLE"}', ""
            if status == 200 and etag and self.headers.get("If-None-Match") == etag:
                status, body = 304, b""
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "private, max-age=15" if status in {200,304} else "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            if etag: self.send_header("ETag", etag)
            self.end_headers()
            if send_body and body: self.wfile.write(body)
        def do_POST(self):
            self.send_response(405); self.send_header("Content-Length", "0"); self.end_headers()
        do_PUT = do_DELETE = do_PATCH = do_POST
        def log_message(self, *_): pass  # Avoid query tokens, private titles and source URLs in hosting logs.
    return Handler
