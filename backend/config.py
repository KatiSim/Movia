from __future__ import annotations

import os
from pathlib import Path
from urllib.parse import urlsplit

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")


def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


HOST = os.getenv("PARSER_HOST", "127.0.0.1")
PORT = int(os.getenv("PARSER_PORT", "5001"))
REQUEST_TIMEOUT = float(os.getenv("PARSER_TIMEOUT", "15"))
MAX_URLS = int(os.getenv("PARSER_MAX_URLS", "50"))
MAX_RESPONSE_BYTES = int(os.getenv("PARSER_MAX_RESPONSE_BYTES", str(5 * 1024 * 1024)))
MAX_REDIRECTS = int(os.getenv("PARSER_MAX_REDIRECTS", "5"))
PLAYWRIGHT_TIMEOUT_MS = int(os.getenv("PLAYWRIGHT_TIMEOUT_MS", "10000"))
DYNAMIC_WAIT_MS = int(os.getenv("DYNAMIC_WAIT_MS", "1200"))
PLAYBACK_TTL_SECONDS = int(os.getenv("PLAYBACK_TTL_SECONDS", "900"))
ALLOW_GENERIC = _env_bool("ALLOW_GENERIC", False)
ALLOW_PRIVATE_SOURCES = _env_bool("ALLOW_PRIVATE_SOURCES", False)
DB_PATH = Path(os.getenv("CATALOG_DB", str(BASE_DIR / "catalog.db"))).expanduser()

# Adaptive parser controls.
ADAPTIVE_MODE = _env_bool("ADAPTIVE_MODE", True)
ADAPTIVE_HARD_RULES_FIRST = _env_bool("ADAPTIVE_HARD_RULES_FIRST", False)
ADAPTIVE_DYNAMIC_FALLBACK = _env_bool("ADAPTIVE_DYNAMIC_FALLBACK", False)
RULE_MIN_CONFIRMATIONS = max(1, int(os.getenv("RULE_MIN_CONFIRMATIONS", "2")))
RULE_DISABLE_FAILURES = max(1, int(os.getenv("RULE_DISABLE_FAILURES", "3")))
RULE_MAX_PER_FIELD = max(1, min(50, int(os.getenv("RULE_MAX_PER_FIELD", "8"))))
PARSER_LOG_PATH = Path(os.getenv("PARSER_LOG_PATH", str(BASE_DIR / "parser.log"))).expanduser()
ADMIN_TOKEN = os.getenv("PARSER_ADMIN_TOKEN", "").strip()

SOURCE_URLS = {
    f"SOURCE_{i:02d}": os.getenv(f"SOURCE_{i:02d}_URL", "").strip()
    for i in range(1, 101)
}


def _host_from_url(value: str) -> str | None:
    if not value:
        return None
    try:
        return (urlsplit(value).hostname or "").lower() or None
    except ValueError:
        return None


SOURCE_HOSTS = {
    source_id: host
    for source_id, value in SOURCE_URLS.items()
    if (host := _host_from_url(value))
}

USER_AGENT = os.getenv(
    "PARSER_USER_AGENT",
    "MoviaMediaParser/1.1 (+local authorized test-media ingestion)",
)
