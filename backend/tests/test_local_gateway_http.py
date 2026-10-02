import json
import threading
import unittest
import urllib.request
from unittest.mock import patch
import streamer

class LocalGatewayHttpTest(unittest.TestCase):
    def request(self,payload):
        with patch.object(streamer,'_local_stream_response',return_value=(200,payload)) as response:
            server=streamer.ThreadedHTTPServer(('127.0.0.1',0),streamer.StreamRequestHandler)
            worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(server.server_port)+'/api/movie/7/stream?refresh=1',timeout=2) as http:
                    result=json.load(http);header=http.headers.get('Cache-Control')
                self.assertEqual(('7',{'refresh':['1']}),response.call_args.args)
                return result,header
            finally:server.shutdown();server.server_close();worker.join(2)
    def test_pending_is_returned_over_the_actual_local_http_handler(self):
        result,header=self.request({'status':'DISCOVERY_PENDING','streams':[],'retryAfterMs':350})
        self.assertEqual('DISCOVERY_PENDING',result['status']);self.assertEqual('no-store',header)
    def test_fresh_variants_are_visible_on_the_next_http_request(self):
        self.request({'status':'DISCOVERY_PENDING','streams':[]})
        result,header=self.request({'status':'READY','streams':[{'voice':'Studio B','quality':'720p','url':'https://media.example/master.m3u8'}]})
        self.assertEqual('Studio B',result['streams'][0]['voice']);self.assertEqual('no-store',header)

    def test_actual_playback_card_http_route_preserves_profile_without_detail_or_provider_work(self):
        payload={"id":"7","streams":[{"url":"https://media.example/master.m3u8","voice":"Studio B",
                 "quality":"Auto","audio_track_index":1,"headers":{"Referer":"https://provider.example"}}]}
        with patch.object(streamer.catalog_api,'get_movie_playback_card',return_value=payload) as card, \
                patch.object(streamer.catalog_api,'get_movie_details',side_effect=AssertionError('Details on playback path')), \
                patch.object(streamer,'resolve_on_demand_streams',side_effect=AssertionError('Provider on card path')):
            server=streamer.ThreadedHTTPServer(('127.0.0.1',0),streamer.StreamRequestHandler)
            worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
            try:
                with urllib.request.urlopen('http://127.0.0.1:'+str(server.server_port)+'/api/movie/7/playback',timeout=2) as http:
                    body=json.load(http);header=http.headers.get('Cache-Control')
                self.assertEqual(payload,body['movie']);self.assertEqual('no-store',header)
                self.assertEqual('7',card.call_args.args[0])
            finally:server.shutdown();server.server_close();worker.join(2)
