# Critical-path work and native crossings

Checks #4, #6, J3.

## #4 Expensive work on the critical path that could be deferred

- **Flag:** heavy compute, parsing, normalization, serialization, I/O or a lock on the synchronous
  request path or the span-finish path, when it could move elsewhere.
- **Not when:** the result is needed right away on that path, or the work is small next to the
  operation it instruments.
- **Confidence / level:** flag-as-consider (whether it can be deferred depends on context). P2; P1
  if it blocks, for example I/O or a contended lock held on application threads.
- **Fix:** move it to a background or writer thread, compute it lazily, or batch it.

## #6 Native-boundary crossing on a hot path

- **Flag:** a native (JNI or other FFI) crossing per span or per item instead of batched, or one
  that transports strings or objects instead of primitives or ids.
- **Not when:** the crossing happens once per flush on a tracer-owned thread.
- **Confidence / level:** flag-with-confidence (the crossing's cost does not depend on the JIT). P2;
  P1 if it blocks or pins threads under concurrency (see J3).
- **Fix:** batch (one crossing per flush, not per item), send interned ids instead of strings, and
  keep crossings off the hot path and off application threads.

## J3 JNI crossing and virtual-thread pinning

- **Flag:** a JNI call reachable from application threads, which may be virtual threads.
- **Not when:** the call runs only on a tracer-owned platform thread (the writer).
- **Confidence / level:** flag-with-confidence for the crossing and the pinning risk;
  flag-as-measure for how large the overhead is. Level as for #6.
- **Fix:** batch at flush on the writer (platform) thread, keep the application's virtual-thread
  path pure Java, and send interned ids instead of strings. `@CriticalNative` fits only short
  operations on primitives (no `JNIEnv`, no object arguments), which is a strong limit.
- **Why:** a JNI call costs about 100 ns–1 µs (state change, argument copying, no inlining across
  it), and string arguments are copied from UTF-16 to UTF-8. A JNI call from a virtual thread pins
  its carrier thread: no other virtual thread can run on that carrier until it returns, so apps
  that rely on virtual threads lose concurrency.
