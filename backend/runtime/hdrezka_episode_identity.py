"""Movia-owned evidence for the episode actually selected by an article page."""
from urllib.parse import urlparse,urljoin
from bs4 import BeautifulSoup
from stream_validation import episode_coordinate


def selected_episode(page,article_id=None):
    soup=BeautifulSoup(page or "", "lxml")
    coordinates=set()
    for tag in soup.select(".b-simple_episode__item.active"):
        if article_id is not None and str(tag.get("data-id") or "") != str(article_id):return None
        season=episode_coordinate(tag.get("data-season_id"))
        episode=episode_coordinate(tag.get("data-episode_id"))
        if season and episode:coordinates.add((season,episode))
    return next(iter(coordinates)) if len(coordinates)==1 else None


def exact_episode_page(page,page_url,season,episode):
    """Follow a public episode link only within this exact article's path."""
    base=urlparse(page_url)
    article=base.path.removesuffix(".html").rstrip("/")
    soup=BeautifulSoup(page or "", "lxml")
    matches=set()
    for tag in soup.select(".b-simple_episode__item[href]"):
        if (episode_coordinate(tag.get("data-season_id")),episode_coordinate(tag.get("data-episode_id"))) != (season,episode):
            continue
        link=urlparse(urljoin(page_url,tag.get("href")))
        if link.scheme not in {"http","https"} or not link.path.startswith(article+"/"):
            continue
        if link.username or link.password:continue
        # Mirrors may advertise the canonical host. Keep the successfully
        # selected provider origin and the verified article-relative path.
        matches.add(base._replace(path=link.path,query=link.query,fragment="").geturl())
    return next(iter(matches)) if len(matches)==1 else None
