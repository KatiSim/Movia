"""SQL expressions shared by catalog readers and additive index migrations."""
USER_VISIBLE_SQL = """
tmdb_id > 0
AND media_type IN ('movie','tv')
AND COALESCE(localized_ru_title, '') != ''
AND poster_url IS NOT NULL
AND poster_url != ''
""".strip()

SCORE_SQL = """
(
    (rating * 1.35) +
    (CASE
        WHEN vote_count >= 50000 THEN 4.5
        WHEN vote_count >= 10000 THEN 3.8
        WHEN vote_count >= 3000 THEN 3.1
        WHEN vote_count >= 1000 THEN 2.4
        WHEN vote_count >= 300 THEN 1.6
        WHEN vote_count >= 100 THEN 0.9
        WHEN vote_count >= 30 THEN 0.3
        ELSE -1.0
    END) +
    (CASE
        WHEN seeders >= 2000 THEN 1.5
        WHEN seeders >= 500 THEN 1.0
        WHEN seeders >= 100 THEN 0.5
        ELSE 0.0
    END)
)
"""
