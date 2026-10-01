"""Shared, parameterized catalog/search filters and strict HTTP query parsing."""
from __future__ import annotations

import math
from datetime import datetime, timezone


class InvalidCatalogArgument(ValueError):
    pass


def parse_catalog_query(params):
    allowed = {'limit', 'offset', 'sort', 'category', 'genre', 'yearFrom', 'yearTo',
               'minRating', 'country', 'mediaType', 'query', 'discover', 'resolution',
               'durationMode', 'newOnly', 'maxAgeRating', 'audioLanguage', 'subtitleLanguage'}
    unknown = set(params) - allowed
    if unknown:
        raise InvalidCatalogArgument('Unknown parameter: ' + sorted(unknown)[0])

    def one(key, default=None):
        values = params.get(key)
        if values is None:
            return default
        if len(values) != 1 and key != 'genre':
            raise InvalidCatalogArgument(key + ': repeated parameter')
        value = values[0]
        if len(value) > 512:
            raise InvalidCatalogArgument(key + ': value too long')
        return value

    def number(key, default, low, high, integer=True):
        raw = one(key, default)
        if raw is None:
            return None
        try:
            if integer and (isinstance(raw, str) and not raw.isdecimal()):
                raise ValueError()
            result = int(raw) if integer else float(raw)
        except (ValueError, TypeError, OverflowError):
            raise InvalidCatalogArgument(key + ': invalid number') from None
        if not math.isfinite(result) or result < low or result > high:
            raise InvalidCatalogArgument(key + ': out of range')
        return result

    def boolean(key):
        value = str(one(key, 'false')).lower()
        if value not in {'0', '1', 'true', 'false'}:
            raise InvalidCatalogArgument(key + ': expected boolean')
        return value in {'1', 'true'}

    sort = str(one('sort', 'POPULAR')).upper()
    if sort not in {'POPULAR', 'RATING', 'NEWEST', 'OLDEST', 'CATEGORY', 'TITLE'}:
        raise InvalidCatalogArgument('sort: unknown value')
    duration = str(one('durationMode', 'ANY')).upper()
    if duration not in {'ANY', 'SHORT', 'MEDIUM', 'LONG'}:
        raise InvalidCatalogArgument('durationMode: unknown value')
    media_type = one('mediaType')
    if media_type is not None and media_type not in {'movie', 'tv'}:
        raise InvalidCatalogArgument('mediaType: unknown value')
    category = one('category')
    if category and category.upper() not in {'ALL', 'MOVIES', 'TV_SERIES', 'LIMITED_SERIES',
           'ANIMATION', 'ANIME', 'DRAMAS_ASIAN', 'DOCUMENTARIES', 'THEATER_MUSICALS', 'STANDUP', 'INTERACTIVE'}:
        raise InvalidCatalogArgument('category: unknown value')
    genres = params.get('genre')
    if genres is not None and (len(genres) > 64 or any(not isinstance(g, str) or not g.strip() or len(g) > 128 for g in genres)):
        raise InvalidCatalogArgument('genre: invalid list')
    year_from = number('yearFrom', None, 1880, 2200)
    year_to = number('yearTo', None, 1880, 2200)
    if year_from is not None and year_to is not None and year_from > year_to:
        raise InvalidCatalogArgument('yearFrom: greater than yearTo')
    return dict(limit=number('limit', 40, 1, 100), offset=number('offset', 0, 0, 10_000_000),
        sort=sort, category=category, genre=genres, year_from=year_from,
        year_to=year_to, min_rating=number('minRating', None, 0, 10, False), country=one('country'),
        media_type=media_type, query_text=one('query'), discover=boolean('discover'),
        resolution=one('resolution'), duration_mode=duration, new_only=boolean('newOnly'),
        max_age_rating=number('maxAgeRating', None, 0, 30), audio_language=one('audioLanguage'),
        subtitle_language=one('subtitleLanguage'))


def _json_array_sql(expression):
    return "CASE WHEN json_valid(" + expression + ") THEN CASE WHEN json_type(" + expression + ")='array' THEN " + expression + " ELSE '[]' END ELSE '[]' END"


def _json_object_sql(alias):
    return "CASE WHEN " + alias + ".type='object' THEN " + alias + ".value ELSE '{}' END"


def _language_key_sql(expression):
    normalized="replace(lower(trim(COALESCE(" + expression + ",''))),'_','-')"
    return "substr(" + normalized + ",1,instr(" + normalized + " || '-','-')-1)"


def extra_conditions(*, resolution=None, duration_mode='ANY', new_only=False,
                     max_age_rating=None, audio_language=None, subtitle_language=None):
    conditions, values = [], []
    streams = "json_each(" + _json_array_sql("streams") + ")"
    stream = _json_object_sql("s")
    if resolution:
        key = resolution.strip().lower()
        aliases = {'4k': ['4k','2160p','2160','uhd'], '2160p':['4k','2160p','2160','uhd'],
                   '1080p':['1080p','1080','fhd','fullhd'], '720p':['720p','720','hd']}.get(key, [key])
        slots = ','.join('?' for _ in aliases)
        conditions.append("(lower(COALESCE(quality,'')) IN (" + slots + ") OR EXISTS (SELECT 1 FROM "
            + streams + " s WHERE lower(COALESCE(json_extract(" + stream + ",'$.quality'),'')) IN (" + slots + ")))")
        values.extend(aliases + aliases)
    if duration_mode == 'SHORT':
        conditions.append('duration_minutes BETWEEN 1 AND 100')
    elif duration_mode == 'MEDIUM':
        conditions.append('duration_minutes BETWEEN 101 AND 109')
    elif duration_mode == 'LONG':
        conditions.append('duration_minutes >= 110')
    if new_only:
        year = datetime.now(timezone.utc).year
        conditions.append('year BETWEEN ? AND ?')
        values.extend([year - 1, year])
    if max_age_rating is not None:
        conditions.append('age_rating IS NOT NULL AND age_rating BETWEEN 0 AND ?')
        values.append(max_age_rating)

    def language_values(language):
        value = language.strip().lower().replace('_','-').split('-',1)[0]
        if value in {'ru', 'rus', 'русский', 'русские', 'russian'}:
            return ['ru', 'rus', 'русский', 'русские', 'Русский', 'РУССКИЙ', 'Русские', 'РУССКИЕ', 'russian']
        if value in {'en', 'eng', 'english', 'английский', 'английские'}:
            return ['en', 'eng', 'english', 'английский', 'английские', 'Английский', 'АНГЛИЙСКИЙ', 'Английские', 'АНГЛИЙСКИЕ']
        return [value]

    if audio_language:
        langs = language_values(audio_language)
        conditions.append('EXISTS (SELECT 1 FROM ' + streams + " s WHERE " + _language_key_sql("json_extract(" + stream + ",'$.language')") + " IN (" + ','.join('?' for _ in langs) + '))')
        values.extend(langs)
    if subtitle_language:
        langs = language_values(subtitle_language)
        subtitle_array=_json_array_sql("json_extract(" + stream + ",'$.subtitles')")
        subtitle=_json_object_sql("sub")
        conditions.append('EXISTS (SELECT 1 FROM ' + streams + " s, json_each(" + subtitle_array + ") sub WHERE " + _language_key_sql("json_extract(" + subtitle + ",'$.language')") + " IN (" + ','.join('?' for _ in langs) + '))')
        values.extend(langs)
    return conditions, values


def membership_conditions(genres, country):
    """Exact membership, matching the Android filter instead of substring LIKE."""
    conditions, values = [], []
    if genres:
        conditions.append("EXISTS (SELECT 1 FROM json_each(" + _json_array_sql("genres") + ") g WHERE g.value IN (" + ','.join('?' for _ in genres) + '))')
        values.extend(genres)
    if country:
        conditions.append("EXISTS (SELECT 1 FROM json_each('[' || replace(json_quote(COALESCE(country,'')),',','\",\"') || ']') c WHERE trim(c.value)=? COLLATE NOCASE)")
        values.append(country.strip())
    return conditions, values
