"""Bounded, single-flight discovery jobs; HTTP readers only enqueue keys."""
from collections import OrderedDict, deque
import threading
import time


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
        self.threads = [threading.Thread(target=self._run, daemon=True, name=f"Movia-discovery-{index}")
                        for index in range(workers)]
        for thread in self.threads: thread.start()

    def _status(self, key):
        if key in self.active: return "RUNNING"
        if key in self.pending: return "QUEUED"
        old = self.completed.get(key)
        if old:
            if self.clock() < old[1]: return old[0]
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
            try:
                ready = bool(self.resolver(key))
            except Exception:
                ready = False
            with self.condition:
                self.active.remove(key)
                state = "READY" if ready else "UNAVAILABLE"
                ttl = self.success_ttl if ready else self.failure_ttl
                self.completed[key] = (state, self.clock() + max(0.0, ttl))
                self.completed.move_to_end(key)
                while len(self.completed) > self.remembered: self.completed.popitem(last=False)
                self.finished += 1
                self.failures += int(not ready)
                self.condition.notify_all()

    def stats(self):
        with self.condition:
            return {"workers": len(self.threads), "active": len(self.active), "pending": len(self.pending),
                    "remembered": len(self.completed), "accepted": self.accepted, "finished": self.finished,
                    "failures": self.failures, "closed": self.closed}

    def close(self, timeout=5.0):
        with self.condition:
            self.closed = True
            self.pending.clear()
            self.condition.notify_all()
        deadline = time.monotonic() + max(0.0, timeout)
        for thread in self.threads: thread.join(max(0.0, deadline-time.monotonic()))
        return not any(thread.is_alive() for thread in self.threads)
