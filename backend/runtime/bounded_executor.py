"""Fixed workers and a fixed submission budget for slow provider calls."""
from concurrent.futures import ThreadPoolExecutor
import threading


class BoundedExecutor:
    def __init__(self, *, workers=2, max_pending=2, name='movia-provider'):
        if not 1 <= workers <= 2 or not 0 <= max_pending <= 64:
            raise ValueError('invalid_executor_budget')
        self.slots = threading.BoundedSemaphore(workers+max_pending)
        self.executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix=name)

    def submit(self, function, *args, **kwargs):
        if not self.slots.acquire(blocking=False): return None
        try: future = self.executor.submit(function, *args, **kwargs)
        except RuntimeError:
            self.slots.release(); return None
        future.add_done_callback(lambda _: self.slots.release())
        return future

    def close(self): self.executor.shutdown(wait=False, cancel_futures=True)
