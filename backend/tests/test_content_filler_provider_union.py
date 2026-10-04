import unittest
from unittest.mock import patch

import content_filler as filler
from provider_discovery import ProviderDiscoveryOutcome


class ContentFillerProviderUnionTests(unittest.TestCase):
    def row(self):
        return {
            'id': 77, 'tmdb_id': 700, 'media_type': 'movie',
            'title': 'Example', 'original_title': 'Example', 'year': 2024,
            'category': 'movies', 'rating': 8.0, 'streams': '[]',
            'playback_url': '', 'link_verified': 0, 'link_updated_at': None,
        }

    def stream(self, provider, voice, quality, suffix):
        return {
            'source': provider, 'provider': provider, 'voice': voice,
            'quality': quality, 'url': f'https://media.example/{suffix}.mp4',
            'catalog_media_id': '77', 'canonical_title': 'Example',
            'canonical_year': 2024, 'canonical_media_type': 'movie',
        }

    def test_filler_persists_clean_provider_and_balancer_union(self):
        filmix = self.stream('Filmix', 'Dub', '1080p', 'filmix')
        rutor = self.stream('Rutor', 'Не указано', '720p', 'rutor')
        outcome = ProviderDiscoveryOutcome([filmix], 'OK', ('filmix',), 0)
        balancer = {'playback_url': rutor['url'], 'voice': rutor['voice'],
                    'quality': rutor['quality'], 'streams': [rutor]}
        captured = {}

        def save(payload):
            captured.update(payload)
            return True

        with patch.object(filler, 'discover_provider_streams', return_value=outcome), \
                patch.object(filler, 'resolve_balancer', return_value=balancer), \
                patch.object(filler, 'get_last_resolution_diagnostics', return_value={'error_count': 0}), \
                patch.object(filler, 'filter_streams_for_content', side_effect=lambda rows, _: rows), \
                patch.object(filler, 'save_content', side_effect=save), \
                patch.object(filler, '_valid_persisted_row', return_value=True), \
                patch.object(filler, 'set_cached_streams'), \
                patch.object(filler, 'PlaybackAvailabilityService') as availability:
            result = filler._process_row(self.row(), 1, 1)

        self.assertEqual('persisted', result['status'])
        self.assertEqual({'Filmix', 'Rutor'}, {row['provider'] for row in captured['streams']})
        self.assertEqual(2, len(captured['streams']))
        availability.return_value.record_discovery.assert_called_once()


if __name__ == '__main__':
    unittest.main()
