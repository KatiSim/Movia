import http.client
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from cloud_api import ReadService, handler, public_payload

class Catalog:
    def __init__(self): self.calls = []
    def get_home_payload(self): return {"movies": [{"id": "158", "title": "Интерстеллар"}]}
    def get_all_genres(self): return ["Фантастика"]
    def get_movie_details(self, ident, **kwargs):
        self.calls.append((ident, kwargs));return {"id": ident, "streams": [], "movie": {"id": ident}} if ident == "158" else None
    def search_catalog(self, text, **kwargs): self.calls.append(kwargs);return {"movies": [], "people": []}
    def get_person_projects(self, name, **kwargs): self.calls.append(kwargs);return {"projects": []}

class CloudApiTests(unittest.TestCase):
    def setUp(self):
        self.catalog = Catalog();self.stream_calls = []
        def streams(ident, season, episode): self.stream_calls.append((ident,season,episode));return 200,{"streams": []}
        self.service = ReadService(self.catalog,streams)
    def test_home_is_ready_without_provider_or_metadata_request(self):
        status,body,_ = self.service.response("/api/home");self.assertEqual(200,status);self.assertIn("Интерстеллар",body.decode());self.assertFalse(self.catalog.calls)
    def test_owned_provider_configuration_is_data_only_without_live_parsing(self):
        status,body,_=self.service.response("/api/providers/config");data=json.loads(body)
        self.assertEqual(200,status);self.assertEqual(1,data["schemaVersion"])
        self.assertTrue(data["providers"]);self.assertFalse(self.catalog.calls);self.assertFalse(self.stream_calls)
        self.assertTrue(all(row["id"] in {1,4,13,16,17,21,23,28,29,31,32,33} for row in data["providers"]))
    def test_provider_configuration_rejects_extra_arguments(self):
        self.assertEqual(400,self.service.response("/api/providers/config?source=http://127.0.0.1")[0])
    def test_movie_details_disable_live_enrichment(self):
        self.assertEqual(200,self.service.response("/api/movie/158")[0]);self.assertEqual(("158",{"enrich":False}),self.catalog.calls[0])
    def test_missing_movie_is_404(self): self.assertEqual(404,self.service.response("/api/movie/999")[0])
    def test_search_does_not_trigger_live_discovery(self):
        self.service.response("/api/search?query=abc");self.assertFalse(self.catalog.calls[0]["discover"])
    def test_people_does_not_trigger_external_metadata(self):
        self.service.response("/api/person?name=Actor");self.assertFalse(self.catalog.calls[0]["enrich"])
    def test_exact_episode_reaches_resolver(self):
        self.service.response("/api/movie/159/stream?season=2&episode=3");self.assertEqual([("159",2,3)],self.stream_calls)
    def test_incomplete_episode_is_rejected(self): self.assertEqual(400,self.service.response("/api/movie/159/stream?season=2")[0])
    def test_duplicate_arguments_are_rejected(self): self.assertEqual(400,self.service.response("/api/search?limit=1&limit=100")[0])
    def test_oversized_queries_are_rejected(self): self.assertEqual(400,self.service.response("/api/search?query="+"x"*300)[0])
    def test_invalid_limits_are_rejected(self):
        for limit in ("0","-1","101","x"):
            self.assertEqual(400,self.service.response("/api/search?limit="+limit)[0])
    def test_absolute_request_target_is_rejected(self): self.assertEqual(400,self.service.response("http://127.0.0.1:8899/api/home")[0])
    def test_local_admin_proxy_and_files_are_not_exposed(self):
        for path in ("/internal/playback-availability/select","/proxy","/stream","/api/clear_cache","/diagnostics","/../../.env"):
            self.assertEqual(404,self.service.response(path)[0])
    def test_cache_has_a_fixed_upper_bound(self):
        for n in range(100): self.service.response("/api/home?number="+str(n))
        self.assertLessEqual(len(self.service.cache),64)
    def test_catalog_payload_cannot_publish_local_or_p2p_urls(self):
        data={"movie":{"streams":[{"url":url} for url in ("http://127.0.0.1/stream","magnet:?xt=x","https://cdn.example/video.mp4")],"playback_url":"file:///private"}}
        result=public_payload(data)["movie"];self.assertEqual([{"url":"https://cdn.example/video.mp4"}],result["streams"]);self.assertEqual("",result["playback_url"])
    def test_credentials_and_private_ip_are_filtered(self):
        streams=[{"url":url} for url in ("https://user:pass@cdn.example/v","http://192.168.1.1/v","http://[::1]/v","http://router.local/v","https://cdn.example/v")]
        self.assertEqual(1,len(public_payload({"streams":streams})["streams"]))
    def test_app_authorization_is_not_published(self):
        result=public_payload({"headers":{"Authorization":"private","Proxy-Authorization":"private","Host":"private","Referer":"https://provider.example"}})
        self.assertEqual({"Referer":"https://provider.example"},result["headers"])
    def test_http_etag_head_and_write_methods(self):
        server=ThreadingHTTPServer(("127.0.0.1",0),handler(self.service));thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            c=http.client.HTTPConnection(*server.server_address,timeout=3)
            c.request("GET","/api/home");r=c.getresponse();etag=r.getheader("ETag");self.assertEqual(200,r.status);r.read()
            c.request("GET","/api/home",headers={"If-None-Match":etag});r=c.getresponse();self.assertEqual(304,r.status);self.assertEqual(b"",r.read())
            c.request("HEAD","/api/home");r=c.getresponse();self.assertEqual(200,r.status);self.assertEqual(b"",r.read())
            for method in ("POST","PUT","DELETE","PATCH"):
                c.request(method,"/api/home");r=c.getresponse();self.assertEqual(405,r.status);r.read()
            c.close()
        finally:server.shutdown();server.server_close();thread.join()

if __name__ == "__main__": unittest.main()
