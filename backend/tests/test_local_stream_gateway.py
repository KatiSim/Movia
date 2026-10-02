import unittest
from local_stream_gateway import stream_gateway_response

class LocalStreamGatewayTest(unittest.TestCase):
    def setUp(self):self.calls=[]
    def service(self,*args,**kwargs):
        self.calls.append((args,kwargs))
        return 200,{'mediaId':'7','status':'READY','streams':[{'url':'https://media.example/master.m3u8'}]}
    def test_legacy_response_fields_are_preserved(self):
        code,body=stream_gateway_response('7',{},self.service)
        self.assertEqual(200,code);self.assertEqual('7',body['id'])
        self.assertEqual(body['streams'][0],body['top_stream'])
        self.assertEqual(body['top_stream']['url'],body['playback_url'])
    def test_refresh_and_exact_episode_reach_shared_service(self):
        stream_gateway_response('m_7',{'season':['2'],'episode':['3'],'refresh':['1']},self.service)
        self.assertEqual(('m_7',2,3),self.calls[0][0]);self.assertTrue(self.calls[0][1]['force_refresh'])
    def test_invalid_parameters_never_invoke_discovery(self):
        for query in ({'season':['0']},{'episode':['x']},{'refresh':['yes']},{'season':['1','2']}):
            self.assertEqual(400,stream_gateway_response('7',query,self.service)[0])
        self.assertFalse(self.calls)
    def test_arbitrary_title_is_not_an_identity_fallback(self):
        self.assertEqual(400,stream_gateway_response('Film title',{},self.service)[0]);self.assertFalse(self.calls)
    def test_pending_response_has_no_fabricated_playable_url(self):
        def pending(*args,**kwargs):return 200,{'status':'DISCOVERY_PENDING','mediaId':'7','streams':[]}
        code,body=stream_gateway_response('7',{},pending)
        self.assertEqual('DISCOVERY_PENDING',body['status']);self.assertEqual('',body['playback_url']);self.assertIsNone(body['top_stream'])
