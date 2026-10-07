"""Bounded, single-flight discovery jobs; HTTP readers only enqueue keys."""
from collections import OrderedDict, deque
import threading
import time
from discovery_outcome import DiscoveryJobResult


class DiscoveryQueue:
    def __init__(self, resolver, *, workers=2, max_pending=64, remembered=128,
                 success_ttl=2.0, failure_ttl=30.0, clock=time.monotonic):
        if not 1 <= workers <= 2 or not 1 <= max_pending <= 64 or not 1 <= remembered <= 512:
            raise ValueError("invalid_discovery_budget")
        self.resolver = resolver
        self.max_pending, self.remembered = max_pending, remembered
        self.success_ttl, self.failure_ttl = success_ttl, failure_ttl
        self.clock = clock
        self.condition = threading.Condition()
        self.pending = deque()
        self.active = set()
        self.completed = OrderedDict()
        self.closed = False
        self.finished = self.failures = self.accepted = 0
        self.errors = 0
        self.threads = [threading.Thread(target=self._run, daemon=True, name=f"Movia-discovery-{index}")
                        for index in range(workers)]
        for thread in self.threads: thread.start()

    def _status(self, key):
        if key in self.active: return "RUNNING"
        if key in self.pending: return "QUEUED"
        old = self.completed.get(key)
        if old:
            if self.clock() < old[1]:
                state,expires,error,job = old
                if job is not None:
                    state,error = self._job_status(job)
                    if state != old[0]:
                        if state == "ERROR":self.errors += 1
                        if state in {"ERROR","UNAVAILABLE"}:self.failures += 1
                        expires = self.clock() + (self.success_ttl if state == "READY" else self.failure_ttl)
                        self.completed[key] = (state,expires,error,job)
                return state
            del self.completed[key]
        return "STOPPED" if self.closed else "IDLE"

    def status(self, key):
        with self.condition: return self._status(key)

    def submit(self, key):
        with self.condition:
            if self.closed or self._status(key) != "IDLE": return False
            if len(self.pending) >= self.max_pending: return False
            self.pending.append(key)
            self.accepted += 1
            self.condition.notify()
            return True

    def _run(self):
        while True:
            with self.condition:
                self.condition.wait_for(lambda: self.closed or self.pending)
                if self.closed: return
                key = self.pending.popleft()
                self.active.add(key)
            error_type = None
            job = None
            try:
                result = self.resolver(key)
                if isinstance(result,DiscoveryJobResult):
                    job = result
                    state,error_type = self._job_status(job)
                else:state = "READY" if bool(result) else "UNAVAILABLE"
            except Exception as error:
                state = "ERROR"
                error_type = type(error).__name__[:80]
            with self.condition:
                self.active.remove(key)
                ttl = self.success_ttl if state == "READY" else self.failure_ttl
                self.completed[key] = (state,self.clock()+max(0.0,ttl),error_type,job)
                self.completed.move_to_end(key)
                while len(self.completed) > self.remembered:self.completed.popitem(last=False)
                self.finished += 1
                self.failures += int(state in {"ERROR","UNAVAILABLE"})
                self.errors += int(state == "ERROR")
                self.condition.notify_all()

    @staticmethod
    def _job_status(job):
        if job.error:return "ERROR",job.error
        detail = job.trace.snapshot() if job.trace is not None else {}
        if detail.get("pendingProviderCount",0):return "PENDING",None
        if detail.get("providerErrorCount",0):
            statuses=detail.get("providerStatuses",[])
            code="DISCOVERY_TIMEOUT" if "DISCOVERY_TIMEOUT" in statuses else "PROVIDER_ERROR"
            return "ERROR",code
        return ("READY",None) if job.ready or detail.get("hasScopedResults") else ("UNAVAILABLE",None)

    def details(self,key):
        with self.condition:
            self._status(key)
            old=self.completed.get(key)
            if old and old[3] is not None and old[3].trace is not None:
                return old[3].trace.snapshot()
            return {}

    def error(self, key):
        with self.condition:
            if self._status(key) != "ERROR": return None
            return self.completed[key][2]

    def stats(self):
        with self.condition:
            return {"workers": len(self.threads), "active": len(self.active), "pending": len(self.pending),
                    "remembered": len(self.completed), "accepted": self.accepted, "finished": self.finished,
                    "failures": self.failures, "errors": self.errors, "closed": self.closed}

    def close(self, timeout=5.0):
        with self.condition:
            self.closed = True
            self.pending.clear()
            self.condition.notify_all()
        deadline = time.monotonic() + max(0.0, timeout)
        for thread in self.threads: thread.join(max(0.0, deadline-time.monotonic()))
        return not any(thread.is_alive() for thread in self.threads)
