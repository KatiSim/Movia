"""Select a verified concrete TorrServer file without guessing engine indexes."""
from typing import Any, Callable, Optional


def select_concrete_file_id(info: dict, *, season: Optional[int], episode: Optional[int],
                            file_index: Optional[int], is_media: Callable[[Any], bool],
                            episode_matches: Callable[[Any, Optional[int], Optional[int]], bool]) -> Optional[str]:
    # Public indexes belong to their originating engine. A TorrServer id must
    # come from its actual metadata; do not reinterpret an aria2/playlist index.
    if file_index is not None or (season is None) != (episode is None):
        return None
    if season is not None and (season < 1 or episode < 1):
        return None
    files = info.get("file_stats") or info.get("files") or []
    if not isinstance(files, list):
        return None
    matches = []
    for item in files:
        if not isinstance(item, dict):
            continue
        path = item.get("path") or item.get("name")
        if not is_media(path):
            continue
        if season is not None and not episode_matches(path, season, episode):
            continue
        try:
            if int(item.get("length") or 0) <= 0:
                return None
        except (ValueError, TypeError, OverflowError):
            return None
        matches.append(item)
    # A movie with several video files or duplicate episode matches requires
    # an explicit verified file-path contract. Never choose the biggest/first.
    if len(matches) != 1:
        return None
    file_id = matches[0].get("id")
    text = str(file_id) if file_id is not None else ""
    if not text.isascii() or not text.isdigit() or int(text) < 1:
        return None
    return text
