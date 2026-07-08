# Category B — Context Propagation

> Referenced from `SKILL.md` Step 4.1. Read this BEFORE picking instrumentation targets.

**Before picking instrumentation targets**, classify the library along the `target_kind` axis:

**Category A — span-creating** (most libraries): performs I/O, makes calls, runs queries. The instrumentation creates spans around those operations.

- HTTP clients/servers, DB clients, messaging clients, RPC frameworks
- Targets: methods that perform the I/O (e.g., `Connection.sendCommand`, `Client.execute`)
- Tests: assert spans exist with correct tags

**Category B — context-propagation** (reactive, async, threading, executor, fiber libraries): does NOT perform I/O directly. It coordinates work that other code performs. Instrumentation captures the active trace context at boundary creation and restores it at boundary crossing — **no spans are created by the module**, it only bridges trace context for spans created by other instrumentations or by user code.

- RxJava, Reactor, CompletableFuture, ListenableFuture, executors, pekko/akka, lettuce async-command queue, ZIO, virtual threads
- Targets: one boundary-crossing type (e.g. `Observable`, `Flowable`, `Single`, `Maybe`, `Completable` for RxJava-shaped libs; `Runnable`/`Callable` for executor-shaped libs)
- Tests: assert that a span created in operation X is still ACTIVE when a callback scheduled by Y runs (parent-child bridging of *user-created* spans, NOT span tags on a target span)

**Reference implementation for Category B:**

`dd-java-agent/instrumentation/rxjava/rxjava-2.0/` — uses `InstrumenterModule.ContextTracking`. 5 type-instrumenters (Observable, Flowable, Single, Maybe, Completable), 5 wrappers (Tracing{Observer,Subscriber,SingleObserver,MaybeObserver,CompletableObserver}), 1 `RxJavaModule.java`, 1 `RxJavaAsyncResultExtension.java`. ~600 LOC total.

## Category B target shape

For each boundary-crossing type, capture:

- `library_class` — FQN of the boundary-crossing type (e.g. `io.reactivex.rxjava3.core.Observable`).
- `capture_method` — capture point (usually the constructor — `<init>` or `isConstructor()`).
- `restore_method` — restore point (usually `subscribe(Observer)` / `subscribe(Subscriber)`).
- `wrapped_argument_type` — FQN of the user callback argument that must be wrapped (e.g. `io.reactivex.rxjava3.core.Observer`).
- `context_key_class` — FQN to key the `contextStore` on (almost always equals `library_class`).
- `wrapper_class_name` — class name of the wrapper to generate (e.g. `TracingObserver`).
- `wrapper_methods` — methods on the wrapped type that must reattach context before delegating (e.g. `["onNext", "onError", "onComplete"]`).

## Tests for Category B

Context-propagation tests assert that **a span created by user code inside the wrapped callback becomes a child of the parent span active when the boundary was created**:

```java
runUnderTrace("parent", () -> {
    constructBoundary()                                  // capture happens here
        .subscribe(item -> userCodeStartsAChildSpan());  // restore happens around the lambda
});
// Assert: 1 trace, 2 spans — child has parent's spanId as parentId.
```

Do NOT assert span kinds, operation names, span tags, or span types on the *target* methods — there are no spans on those methods.

## Choosing between method overloads (Category B)

When a reactive boundary type exposes multiple overloads of the subscribe / invoke method — e.g. `Flowable.subscribe(Subscriber)` vs `Flowable.subscribe(FlowableSubscriber)`, or `Mono.subscribe(Subscriber)` vs `Mono.subscribe(CoreSubscriber)` — hook the **most specific framework-internal interface**, not the public wrapper.

**Why:** the public overload typically delegates to the internal one. Hooking the public overload causes double-wrapping (every subscription flows through the wrapper twice) and the internal overload sees a wrapped argument of the wrong runtime type.

**How to identify the right overload:** read the framework source. The public method usually calls a `subscribeActual(...)` or similar protected method that takes the framework-internal interface. If the framework documents one of the overloads as "for internal use only" or marks it `public final`, that's the implementation method — hook it.

**Reference:** dd-trace-java's `rxjava-2.0` hooks `subscribe(Observer)` (the implementation). The RxJava 3 reference instrumentation hooks `subscribe(FlowableSubscriber)`, not `subscribe(Subscriber)`.

## When NOT to use Category B

If the library DOES perform I/O — sends HTTP requests, runs DB queries, makes RPC calls, talks to a broker, reads/writes a cache — it is **span-creating**, not context-propagation.

Hybrid libraries that BOTH coordinate work AND perform I/O usually get one span-creating instrumentation for the I/O path and (optionally) one context-propagation instrumentation for the coordination path. `lettuce-5.0` is an example: there is a span-creating instrumentation for Redis commands and a separate context-propagation instrumentation for the async command queue.
