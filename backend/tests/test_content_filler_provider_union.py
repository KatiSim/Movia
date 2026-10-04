import unittest
from concurrent.futures import Future
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

    def test_persistence_rewrites_movie_magnet_to_provider_contract_identity(self):
        row = self.row()
        magnet = {
            'source': 'Rutor', 'provider': 'Rutor',
            'url': 'magnet:?xt=urn:btih:' + 'a' * 40 + '&tr=https%3A%2F%2Fold',
            'voice': 'Дубляж', 'quality': '1080p', 'seeders': 17,
            'title': 'Example 2024 1080p | D',
        }
        direct = self.stream('Collaps', 'Dub', 'Auto', 'direct')
        with patch.dict('os.environ', {'MOVIA_ENABLE_TORRENT_PROVIDER_CONTRACT': '1'}, clear=False):
            out = filler._rewrite_torrent_rows_for_persistence([direct, magnet], row)
        magnets = [item for item in out if str(item.get('url') or '').startswith('magnet:?')]
        directs = [item for item in out if str(item.get('url') or '').startswith('https://')]
        self.assertEqual(1, len(magnets))
        self.assertEqual(1, len(directs))
        self.assertEqual(direct['url'], directs[0]['url'])
        self.assertEqual('a' * 40, magnets[0]['info_hash'])
        self.assertEqual(17, magnets[0]['seeders'])
        self.assertEqual('77', magnets[0]['catalog_media_id'])
        self.assertTrue(magnets[0].get('logical_source_id'))
        self.assertTrue(magnets[0].get('provider_item_id'))

    def test_persistence_does_not_fabricate_episode_from_tv_card_level_pack(self):
        row = self.row()
        row.update(media_type='tv', category='tv_series')
        generic = {
            'source': 'Rutor', 'provider': 'Rutor',
            'url': 'magnet:?xt=urn:btih:' + 'b' * 40,
            'voice': 'Dub', 'quality': '1080p', 'seeders': 9,
            'title': 'Example Season Pack',
        }
        exact_episode = dict(generic, url='magnet:?xt=urn:btih:' + 'c' * 40, season=1, episode=2, title='S01E02')
        with patch.dict('os.environ', {'MOVIA_ENABLE_TORRENT_PROVIDER_CONTRACT': '1'}, clear=False):
            out = filler._rewrite_torrent_rows_for_persistence([generic, exact_episode], row)
        self.assertEqual(1, len(out))
        self.assertEqual('b' * 40, out[0]['info_hash'])
        self.assertNotIn('season', out[0])
        self.assertNotIn('episode', out[0])
        self.assertEqual('tv', out[0]['canonical_media_type'])

    def test_pending_balancer_cannot_block_torrent_enrichment(self):
        rutor = self.stream('Rutor', 'Studio B', '720p', 'rutor')
        torrent = {'playback_url': rutor['url'], 'voice': rutor['voice'],
                   'quality': rutor['quality'], 'streams': [rutor]}
        provider_future = Future()
        provider_future.set_result(ProviderDiscoveryOutcome([], 'NO_RESULTS', (), 0))
        balancer_future = Future()
        torrent_future = Future()
        torrent_future.set_result(torrent)
        captured = {}

        def save(payload):
            captured.update(payload)
            return True

        with patch.dict('os.environ', {
                    'MOVIA_BACKGROUND_BULK': '1',
                    'MOVIA_BACKGROUND_TORRENT_LOOKUP': '1',
                    'MOVIA_CLOUD_MODE': '0',
                }, clear=False), \
                patch.object(filler._FILL_PROVIDER_EXECUTOR, 'submit', side_effect=[provider_future, balancer_future]), \
                patch.object(filler._FILL_TORRENT_EXECUTOR, 'submit', return_value=torrent_future) as torrent_submit, \
                patch.object(filler, 'wait', return_value=({provider_future}, {balancer_future})) as wait_call, \
                patch.object(filler, 'filter_streams_for_content', side_effect=lambda rows, _: rows), \
                patch.object(filler, 'save_content', side_effect=save), \
                patch.object(filler, '_valid_persisted_row', return_value=True), \
                patch.object(filler, '_persisted_variant_coverage', return_value=None), \
                patch.object(filler, 'set_cached_streams'), \
                patch.object(filler, 'PlaybackAvailabilityService'):
            result = filler._process_row(self.row(), 1, 1)

        self.assertEqual('persisted', result['status'])
        self.assertEqual({'Rutor'}, {row['provider'] for row in captured['streams']})
        torrent_submit.assert_called_once()
        wait_call.assert_called_once()
        self.assertTrue(balancer_future.cancelled())

    def test_filler_persists_clean_provider_and_balancer_union(self):
        filmix = self.stream('Filmix', 'Dub', '1080p', 'filmix')
        collaps = self.stream('Collaps', 'Studio A', 'Auto', 'collaps')
        rutor = self.stream('Rutor', 'Studio B', '720p', 'rutor')
        outcome = ProviderDiscoveryOutcome([filmix], 'OK', ('filmix',), 0)
        balancer = {'playback_url': collaps['url'], 'voice': collaps['voice'],
                    'quality': collaps['quality'], 'streams': [collaps]}
        torrent = {'playback_url': rutor['url'], 'voice': rutor['voice'],
                   'quality': rutor['quality'], 'streams': [rutor]}
        captured = {}

        def save(payload):
            captured.update(payload)
            return True

        with patch.dict('os.environ', {
                    'MOVIA_BACKGROUND_BULK': '1',
                    'MOVIA_BACKGROUND_TORRENT_LOOKUP': '1',
                    'MOVIA_CLOUD_MODE': '0',
                }, clear=False), \
                patch.object(filler, 'discover_provider_streams', return_value=outcome), \
                patch.object(filler, 'resolve_balancer', return_value=balancer), \
                patch.object(filler, 'resolve_torrent', return_value=torrent) as torrent_resolve, \
                patch.object(filler, 'get_last_resolution_diagnostics', return_value={'error_count': 0}), \
                patch.object(filler, 'filter_streams_for_content', side_effect=lambda rows, _: rows), \
                patch.object(filler, 'save_content', side_effect=save), \
                patch.object(filler, '_valid_persisted_row', return_value=True), \
                patch.object(filler, 'set_cached_streams'), \
                patch.object(filler, 'PlaybackAvailabilityService') as availability:
            result = filler._process_row(self.row(), 1, 1)

        self.assertEqual('persisted', result['status'])
        self.assertEqual({'Filmix', 'Collaps', 'Rutor'}, {row['provider'] for row in captured['streams']})
        self.assertEqual(3, len(captured['streams']))
        torrent_resolve.assert_called_once()
        availability.return_value.record_discovery.assert_called_once()


if __name__ == '__main__':
    unittest.main()
