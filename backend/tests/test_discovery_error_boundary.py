import time,unittest
from types import SimpleNamespace
from discovery_queue import DiscoveryQueue
from catalog_stream_service import CatalogStreamService

class DiscoveryErrorBoundaryTest(unittest.TestCase):
    def wait(self,predicate):
        end=time.monotonic()+3
        while not predicate() and time.monotonic()<end:time.sleep(.005)
        self.assertTrue(predicate())
    def test_empty_inventory_is_unavailable_without_worker_error(self):
        queue=DiscoveryQueue(lambda key:False,workers=1)
        self.addCleanup(queue.close);key=("1",None,None);queue.submit(key)
        self.wait(lambda:queue.status(key)=="UNAVAILABLE")
        self.assertEqual(0,queue.stats()["errors"]);self.assertIsNone(queue.error(key))
    def test_error_cache_expires_and_does_not_publish_exception_message(self):
        clock=[100.]
        def fail(key):raise ValueError("https://private.test/signed-token")
        queue=DiscoveryQueue(fail,workers=1,clock=lambda:clock[0])
        self.addCleanup(queue.close);key=("1",None,None);queue.submit(key)
        self.wait(lambda:queue.status(key)=="ERROR")
        self.assertEqual("ValueError",queue.error(key))
        clock[0]=131
        self.assertEqual("IDLE",queue.status(key));self.assertIsNone(queue.error(key))
    def service(self,rows):
        card={"id":1,"title":"Fixture","year":2020,"media_type":"movie","streams":rows}
        def fail(**kwargs):raise RuntimeError("private backend details")
        catalog=SimpleNamespace(get_movie_playback_card=lambda ident:card)
        runtime=SimpleNamespace(cloud_exposable_streams=lambda items:items,
            _direct_stream_expiry_seconds=lambda row:None,resolve_on_demand_streams=fail)
        service=CatalogStreamService(catalog,runtime,lambda items,card:items,workers=1)
        self.addCleanup(service.close);return service
    def test_worker_exception_reaches_public_error_code(self):
        service=self.service([])
        service("1",None,None,force_refresh=True)
        self.wait(lambda:service.queue.status(("1",None,None))=="ERROR")
        code,body=service("1",None,None)
        self.assertEqual(200,code);self.assertEqual("ERROR",body["status"])
        self.assertEqual("DISCOVERY_ERROR",body["errorCode"]);self.assertEqual("RuntimeError",body["discoveryError"])
        self.assertEqual([],body["streams"])
    def test_failed_refresh_preserves_existing_inventory_and_reports_error(self):
        row={"url":"https://fixture.test/movie.mp4","source":"HDRezka","provider":"HDRezka",
            "voice":"Studio","quality":"720p"}
        service=self.service([row]);service("1",None,None,force_refresh=True)
        self.wait(lambda:service.queue.status(("1",None,None))=="ERROR")
        code,body=service("1",None,None)
        self.assertEqual(200,code);self.assertEqual("READY",body["status"])
        self.assertEqual("ERROR",body["discoveryStatus"]);self.assertEqual("DISCOVERY_ERROR",body["errorCode"])
        self.assertEqual(1,len(body["streams"]));self.assertEqual("1",body["streams"][0]["catalog_media_id"])
