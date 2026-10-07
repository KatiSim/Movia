import copy,threading,time,unittest
from concurrent.futures import Future
from types import SimpleNamespace
from discovery_outcome import DiscoveryTrace,DiscoveryJobResult,ResolvedStreams
from discovery_queue import DiscoveryQueue
from catalog_stream_service import CatalogStreamService
from provider_discovery import ProviderDiscoveryOutcome

def done(value=None,error=None):
    future=Future()
    if error is not None:future.set_exception(error)
    else:future.set_result(value)
    return future

class DiscoveryTraceTests(unittest.TestCase):
    def test_completed_provider_error_is_not_missing_inventory(self):
        trace=DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"PROVIDER_ERROR",error_count=1))})
        self.assertEqual(1,trace.snapshot()["providerErrorCount"])
        self.assertEqual(0,trace.snapshot()["pendingProviderCount"])

    def test_pending_provider_is_not_a_completed_error(self):
        child=Future()
        trace=DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"PROVIDER_TIMEOUT",pending_futures=(child,)))})
        self.assertEqual(1,trace.snapshot()["pendingProviderCount"])
        self.assertEqual(0,trace.snapshot()["providerErrorCount"])
        child.set_result(ProviderDiscoveryOutcome([],"NO_MATCH"))
        self.assertEqual(0,trace.snapshot()["pendingProviderCount"])
        self.assertEqual(0,trace.snapshot()["providerErrorCount"])

    def test_late_error_reaches_same_exact_invocation(self):
        child=Future()
        trace=DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"PROVIDER_TIMEOUT",pending_futures=(child,)))})
        trace.snapshot()
        child.set_result(ProviderDiscoveryOutcome([],"PROVIDER_ERROR",error_count=1))
        self.assertEqual(1,trace.snapshot()["providerErrorCount"])
        self.assertIn("PROVIDER_ERROR",trace.snapshot()["providerStatuses"])

    def test_late_filtered_success_is_preserved(self):
        child=Future()
        trace=DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"PROVIDER_TIMEOUT",pending_futures=(child,)))},
            validator=lambda rows:any(row.get("catalog_media_id")=="42" for row in rows))
        trace.snapshot()
        child.set_result(ProviderDiscoveryOutcome([{"catalog_media_id":"42"}],"OK"))
        self.assertTrue(trace.snapshot()["hasScopedResults"])

    def test_another_card_does_not_count_as_scoped_success(self):
        trace=DiscoveryTrace({"registry":done([{"catalog_media_id":"43"}])},
            validator=lambda rows:any(row.get("catalog_media_id")=="42" for row in rows))
        self.assertFalse(trace.snapshot()["hasScopedResults"])

    def test_executor_rejection_cannot_be_classified_as_no_results(self):
        trace=DiscoveryTrace({"registry":None})
        self.assertEqual(1,trace.snapshot()["providerErrorCount"])
        self.assertIn("EXECUTOR_BUSY",trace.snapshot()["providerStatuses"])

    def test_future_exception_does_not_publish_secret_message(self):
        trace=DiscoveryTrace({"registry":done(error=RuntimeError("https://secret.example/token"))})
        self.assertEqual(["PROVIDER_ERROR"],trace.snapshot()["providerStatuses"])
        self.assertNotIn("secret",str(trace.snapshot()))

    def test_deadline_is_timeout_not_external_unavailability(self):
        clock=[100.]
        trace=DiscoveryTrace({"registry":Future()},clock=lambda:clock[0])
        self.assertEqual(1,trace.snapshot()["pendingProviderCount"])
        clock[0]=131.
        self.assertIn("DISCOVERY_TIMEOUT",trace.snapshot()["providerStatuses"])
        self.assertEqual(1,trace.snapshot()["providerErrorCount"])

    def test_success_does_not_hide_another_provider_error(self):
        trace=DiscoveryTrace({"balancer":done([{"catalog_media_id":"42"}]),
            "registry":done(ProviderDiscoveryOutcome([],"PROVIDER_ERROR",error_count=1))})
        detail=trace.snapshot()
        self.assertTrue(detail["hasScopedResults"])
        self.assertEqual(1,detail["providerErrorCount"])

    def test_cancelled_provider_is_not_clean_absence(self):
        cancelled=Future();cancelled.cancel()
        trace=DiscoveryTrace({"registry":cancelled})
        self.assertIn("PROVIDER_CANCELLED",trace.snapshot()["providerStatuses"])
        self.assertEqual(1,trace.snapshot()["providerErrorCount"])

    def test_completed_futures_do_not_retain_url_payloads(self):
        trace=DiscoveryTrace({"registry":done([{"url":"https://secret.example/token"}])})
        trace.snapshot()
        self.assertNotIn("secret",str(trace.branches))

class DiscoveryTraceBoundaryTests(unittest.TestCase):
    def wait(self,predicate):
        end=time.monotonic()+3
        while not predicate() and time.monotonic()<end:time.sleep(.005)
        self.assertTrue(predicate())

    def service(self,trace,rows=()):
        card={"id":"42","title":"Fixture","media_type":"movie","year":2024,"streams":list(rows)}
        def resolve(**kwargs):return ResolvedStreams((),trace=trace)
        runtime=SimpleNamespace(cloud_exposable_streams=lambda items:items,
            _direct_stream_expiry_seconds=lambda row:None,resolve_on_demand_streams=resolve)
        catalog=SimpleNamespace(get_movie_playback_card=lambda ident:copy.deepcopy(card))
        service=CatalogStreamService(catalog,runtime,lambda rows,card:rows,workers=1)
        self.addCleanup(service.close)
        return service

    def test_real_worker_result_carries_provider_error_to_http_contract(self):
        service=self.service(DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"PROVIDER_ERROR",error_count=1))}))
        service("42",None,None)
        self.wait(lambda:service.queue.status(("42",None,None))=="ERROR")
        _,body=service("42",None,None)
        self.assertEqual("ERROR",body["status"])
        self.assertEqual("DISCOVERY_ERROR",body["errorCode"])
        self.assertEqual(1,body["providerErrorCount"])

    def test_pending_late_provider_is_single_flight_then_becomes_error(self):
        child=Future()
        service=self.service(DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"PROVIDER_TIMEOUT",pending_futures=(child,)))}))
        service("42",None,None)
        self.wait(lambda:service.queue.status(("42",None,None))=="PENDING")
        for _ in range(20):
            _,body=service("42",None,None)
            self.assertEqual("DISCOVERY_PENDING",body["status"])
            self.assertTrue(body["refreshing"])
        self.assertEqual(1,service.queue.stats()["accepted"])
        child.set_exception(RuntimeError("private transport message"))
        _,body=service("42",None,None)
        self.assertEqual("ERROR",body["status"])
        self.assertEqual("PROVIDER_ERROR",body["discoveryError"])
        self.assertNotIn("private",str(body))

    def test_existing_inventory_survives_failed_refresh(self):
        row={"url":"https://media.example/film.mp4","source":"Native","provider":"Native","quality":"720p","voice":"Dub"}
        service=self.service(DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"PROVIDER_ERROR",error_count=1))}),[row])
        service("42",None,None,force_refresh=True)
        self.wait(lambda:service.queue.status(("42",None,None))=="ERROR")
        _,body=service("42",None,None)
        self.assertEqual("READY",body["status"])
        self.assertEqual("ERROR",body["discoveryStatus"])
        self.assertEqual(1,len(body["streams"]))

    def test_completed_no_match_remains_unavailable_without_error(self):
        service=self.service(DiscoveryTrace({"registry":done(ProviderDiscoveryOutcome([],"NO_MATCH"))}))
        service("42",None,None)
        self.wait(lambda:service.queue.status(("42",None,None))=="UNAVAILABLE")
        _,body=service("42",None,None)
        self.assertEqual("UNAVAILABLE",body["status"])
        self.assertEqual(0,body["providerErrorCount"])
        self.assertNotIn("errorCode",body)

    def test_queue_late_success_and_deadline_remain_distinct(self):
        clock=[100.]
        late=Future()
        trace=DiscoveryTrace({"registry":late},clock=lambda:clock[0])
        queue=DiscoveryQueue(lambda key:DiscoveryJobResult(False,trace),workers=1,clock=lambda:clock[0])
        self.addCleanup(queue.close)
        key=("42",1,2);queue.submit(key)
        self.wait(lambda:queue.status(key)=="PENDING")
        late.set_result([{"season":1,"episode":2}])
        self.assertEqual("READY",queue.status(key))

    def test_queue_future_error_expiry_allows_a_new_attempt(self):
        clock=[100.]
        trace=DiscoveryTrace({"registry":done(error=ValueError("private"))})
        queue=DiscoveryQueue(lambda key:DiscoveryJobResult(False,trace),workers=1,clock=lambda:clock[0])
        self.addCleanup(queue.close)
        key=("42",None,None);queue.submit(key)
        self.wait(lambda:queue.status(key)=="ERROR")
        self.assertFalse(queue.submit(key))
        clock[0]=131.
        self.assertEqual("IDLE",queue.status(key))
        self.assertTrue(queue.submit(key))
