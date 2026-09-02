import os
import time
import requests
import urllib3
from pathlib import Path
from typing import List, Dict, Optional, Any

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover - deployment fallback
    load_dotenv = None

from catalog_localization import (
    clean_title,
    is_russian_display_title,
    parse_alternative_titles,
    russian_alternative_titles,
)

from metadata_quality import (
    bayesian_rating,
    category_for,
    country_codes_from_tmdb,
    country_from_tmdb,
    creators_from_tmdb,
    directors_from_credits,
)

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

if load_dotenv is not None:
    load_dotenv(Path(__file__).with_name(".env"), override=False)

class TMDbClient:
    def __init__(
        self,
        api_key: Optional[str] = None,
        language: str = "ru-RU",
        access_token: Optional[str] = None,
    ):
        self.api_key = (api_key or os.getenv("TMDB_API_KEY", "")).strip()
        self.access_token = (access_token or os.getenv("TMDB_ACCESS_TOKEN", "")).strip()
        self.language = language
        self.base_url = "https://api.themoviedb.org/3"
        self.poster_base = "https://image.tmdb.org/t/p/w500"
        self.backdrop_base = "https://image.tmdb.org/t/p/original"
        self.person_base = "https://image.tmdb.org/t/p/w342"
        self.session = requests.Session()

    def _get(
        self,
        endpoint: str,
        params: Optional[Dict[str, Any]] = None,
        *,
        max_retries: int = 0,
    ) -> Optional[Dict[str, Any]]:
        url = f"{self.base_url}{endpoint}"
        default_params = {"language": self.language}
        if self.api_key:
            default_params["api_key"] = self.api_key
        if params:
            default_params.update(params)
        headers = {"Accept": "application/json", "User-Agent": "Movia/1.0"}
        if self.access_token:
            headers["Authorization"] = f"Bearer {self.access_token}"
        if not self.api_key and not self.access_token:
            return None
        # Retries are opt-in.  Ordinary detail/list callers retain the
        # historical single-request behavior; the bounded catalog worker can
        # request one retry for a failed discovery page.
        try:
            max_retries = max(0, min(1, int(max_retries)))
        except (TypeError, ValueError, OverflowError):
            max_retries = 0
        retryable_statuses = {429, 500, 502, 503, 504}
        max_retry_delay = 2.0
        for attempt in range(max_retries + 1):
            try:
                resp = self.session.get(url, params=default_params, headers=headers, timeout=10)
            except Exception:
                if attempt >= max_retries:
                    return None
                time.sleep(min(max_retry_delay, 0.5 * (2 ** attempt)))
                continue

            if resp.status_code == 200:
                try:
                    payload = resp.json()
                except (TypeError, ValueError):
                    return None
                return payload if isinstance(payload, dict) else None

            if resp.status_code not in retryable_statuses or attempt >= max_retries:
                return None

            retry_after = None
            try:
                retry_after = float((getattr(resp, "headers", {}) or {}).get("Retry-After"))
            except (TypeError, ValueError):
                retry_after = None
            if retry_after is not None:
                retry_after = max(0.0, retry_after)
                if retry_after > max_retry_delay:
                    return None
                delay = retry_after
            else:
                delay = min(max_retry_delay, 0.5 * (2 ** attempt))
            time.sleep(delay)
        return None

    def get_popular_movies(self, page: int = 1) -> List[Dict[str, Any]]:
        data = self._get("/movie/popular", {"page": page})
        return data.get("results", []) if data else []

    def get_popular_tv(self, page: int = 1) -> List[Dict[str, Any]]:
        data = self._get("/tv/popular", {"page": page})
        return data.get("results", []) if data else []

    def _parse_cast(self, credits: Dict[str, Any]) -> List[Dict[str, Any]]:
        result = []
        for person in (credits or {}).get("cast", [])[:12]:
            name = person.get("name")
            if not name:
                continue
            profile_path = person.get("profile_path")
            result.append({
                "name": name,
                "photo_url": f"{self.person_base}{profile_path}" if profile_path else None,
                "role": person.get("character") or None,
            })
        return result

    def get_movie_details(self, movie_id: int) -> Optional[Dict[str, Any]]:
        data = self._get(
            f"/movie/{movie_id}",
            {"append_to_response": "credits,alternative_titles,external_ids"},
            max_retries=1,
        )
        if not data:
            return None

        release_date = str(data.get("release_date") or "")
        year = int(release_date[:4]) if len(release_date) >= 4 and release_date[:4].isdigit() else 0
        genres = [str(g.get("name") or "").strip() for g in data.get("genres", []) if isinstance(g, dict) and g.get("name")]
        credits = data.get("credits") or {}
        cast = self._parse_cast(credits)
        directors = directors_from_credits(credits)
        director = ", ".join(directors)

        poster_path = data.get("poster_path")
        backdrop_path = data.get("backdrop_path")
        poster_url = f"{self.poster_base}{poster_path}" if poster_path else None
        backdrop_url = f"{self.backdrop_base}{backdrop_path}" if backdrop_path else poster_url

        alternative_titles = parse_alternative_titles(data.get("alternative_titles"))
        official_title = clean_title(data.get("title"))
        localized_title = official_title if is_russian_display_title(
            official_title, data.get("original_title")
        ) else (russian_alternative_titles(alternative_titles) or [None])[0]

        vote_average = float(data.get("vote_average") or 0.0)
        vote_count = int(data.get("vote_count") or 0)
        country_codes = country_codes_from_tmdb(data, "movie")
        collection = data.get("belongs_to_collection") if isinstance(data.get("belongs_to_collection"), dict) else {}
        external_ids = data.get("external_ids") if isinstance(data.get("external_ids"), dict) else {}

        return {
            "tmdb_id": int(data.get("id") or movie_id),
            "imdb_id": str(external_ids.get("imdb_id") or data.get("imdb_id") or ""),
            "media_type": "movie",
            "title": official_title or data.get("original_title") or "Без названия",
            "localized_ru_title": localized_title or "",
            "alternative_titles": alternative_titles,
            "original_title": str(data.get("original_title") or ""),
            "year": year,
            "rating": bayesian_rating(vote_average, vote_count),
            "vote_average": vote_average,
            "vote_count": vote_count,
            "duration_minutes": int(data.get("runtime") or 0),
            "synopsis": str(data.get("overview") or ""),
            "poster_url": poster_url,
            "backdrop_url": backdrop_url,
            "genres": genres,
            "cast": cast,
            "director": director,
            "creators": [],
            "country": country_from_tmdb(data, "movie"),
            "category": category_for("movie", genres, country_codes),
            "collection_id": int(collection.get("id") or 0),
            "seasons_count": 0,
            "episodes_count": 0,
            "season_episode_counts": [],
            "metadata_source": "tmdb_detail",
        }

    def get_tv_details(self, tv_id: int) -> Optional[Dict[str, Any]]:
        data = self._get(
            f"/tv/{tv_id}",
            {"append_to_response": "credits,alternative_titles,external_ids"},
            max_retries=1,
        )
        if not data:
            return None

        first_air = str(data.get("first_air_date") or "")
        year = int(first_air[:4]) if len(first_air) >= 4 and first_air[:4].isdigit() else 0
        genres = [str(g.get("name") or "").strip() for g in data.get("genres", []) if isinstance(g, dict) and g.get("name")]
        credits = data.get("credits") or {}
        cast = self._parse_cast(credits)
        creators = creators_from_tmdb(data, "tv")

        poster_path = data.get("poster_path")
        backdrop_path = data.get("backdrop_path")
        poster_url = f"{self.poster_base}{poster_path}" if poster_path else None
        backdrop_url = f"{self.backdrop_base}{backdrop_path}" if backdrop_path else poster_url
        season_rows = sorted(
            [x for x in data.get("seasons", []) if isinstance(x, dict) and int(x.get("season_number") or 0) > 0],
            key=lambda item: int(item.get("season_number") or 0),
        )
        max_season = max([int(x.get("season_number") or 0) for x in season_rows], default=0)
        season_episode_counts = [0] * max_season
        for season in season_rows:
            number = int(season.get("season_number") or 0)
            if 1 <= number <= max_season:
                season_episode_counts[number - 1] = int(season.get("episode_count") or 0)

        alternative_titles = parse_alternative_titles(data.get("alternative_titles"))
        official_title = clean_title(data.get("name"))
        localized_title = official_title if is_russian_display_title(
            official_title, data.get("original_name")
        ) else (russian_alternative_titles(alternative_titles) or [None])[0]
        vote_average = float(data.get("vote_average") or 0.0)
        vote_count = int(data.get("vote_count") or 0)
        country_codes = country_codes_from_tmdb(data, "tv")
        external_ids = data.get("external_ids") if isinstance(data.get("external_ids"), dict) else {}
        runtimes = data.get("episode_run_time") or []
        runtime = int(runtimes[0] or 0) if isinstance(runtimes, list) and runtimes else 0

        return {
            "tmdb_id": int(data.get("id") or tv_id),
            "imdb_id": str(external_ids.get("imdb_id") or ""),
            "media_type": "tv",
            "title": official_title or data.get("original_name") or "Без названия",
            "localized_ru_title": localized_title or "",
            "alternative_titles": alternative_titles,
            "original_title": str(data.get("original_name") or ""),
            "year": year,
            "rating": bayesian_rating(vote_average, vote_count),
            "vote_average": vote_average,
            "vote_count": vote_count,
            "duration_minutes": runtime,
            "seasons_count": int(data.get("number_of_seasons") or max_season or 0),
            "episodes_count": int(data.get("number_of_episodes") or sum(season_episode_counts)),
            "season_episode_counts": season_episode_counts,
            "synopsis": str(data.get("overview") or ""),
            "poster_url": poster_url,
            "backdrop_url": backdrop_url,
            "genres": genres,
            "cast": cast,
            # A TV show does not have one movie-style director. Keep that field
            # empty and expose the authoritative creators separately.
            "director": "",
            "creators": creators,
            "country": country_from_tmdb(data, "tv"),
            "category": category_for("tv", genres, country_codes),
            "collection_id": 0,
            "metadata_source": "tmdb_detail",
        }

tmdb = TMDbClient()
