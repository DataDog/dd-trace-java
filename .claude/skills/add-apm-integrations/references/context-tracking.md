# Context-Tracking Instrumentation

> Referenced from `SKILL.md` Step 4.1. Read this BEFORE picking instrumentation targets.

## Which kind of instrumentation to write

Before picking instrumentation targets, decide which kind of instrumentation the library needs. dd-trace-java has two:

**Span-creating instrumentation** — the module extends `InstrumenterModule.Tracing`. Advice creates spans around the library's operations. This is the common case: HTTP clients/servers, DB clients, messaging clients, RPC frameworks. Targets are methods that perform the I/O (e.g. `Connection.sendCommand`, `Client.execute`). Tests assert spans exist with correct tags.

**Context-tracking instrumentation** — the module extends `InstrumenterModule.ContextTracking`. Advice captures the active trace context at a boundary and restores it when work crosses that boundary. The module creates **no spans** itself; it only bridges trace context for spans created by other instrumentations or by user code. This is the case for reactive libraries (RxJava, Reactor), async/executor libraries (`CompletableFuture`, `ListenableFuture`, executors), coroutine/actor libraries (Kotlin coroutines, pekko/akka), and any code that schedules a callback to run later or on another thread.

- Typical libraries: RxJava, Reactor, Mutiny, CompletableFuture, ListenableFuture, executors, pekko/akka, lettuce async-command queue, ZIO, virtual threads
- Typical targets: the boundary-crossing type's constructor (capture context) + its subscribe/execute/schedule method (restore context around the callback)
- For RxJava-shaped libraries, one instrumentation per reactive type (e.g. `Observable`, `Flowable`, `Single`, `Maybe`, `Completable`)
- For executor-shaped libraries, one instrumentation for the executor's submit/execute methods wrapping `Runnable`/`Callable`

## Reference implementation

**`dd-java-agent/instrumentation/rxjava/rxjava-2.0/`** — the canonical context-tracking module. It uses `InstrumenterModule.ContextTracking`. Contents: 5 type-instrumenters (`ObservableInstrumentation`, `FlowableInstrumentation`, `SingleInstrumentation`, `MaybeInstrumentation`, `CompletableInstrumentation`), 5 wrapper classes (`TracingObserver`, `TracingSubscriber`, `TracingSingleObserver`, `TracingMaybeObserver`, `TracingCompletableObserver`), 1 `RxJavaModule.java`, 1 `RxJavaAsyncResultExtension.java`. ~600 LOC total. Read this before writing a new context-tracking module.

## What a context-tracking instrumentation captures

For each boundary-crossing type in the library, the instrumentation needs to identify:

- **The boundary type** — the FQN of the type that gets subscribed to / executed / scheduled (e.g. `io.reactivex.rxjava3.core.Observable`).
- **The capture point** — where to grab the active trace context. Usually the constructor (`<init>` or `isConstructor()`).
- **The restore point** — where the captured context needs to be reactivated. Usually the `subscribe(...)` / `execute(...)` / `schedule(...)` method that runs the user-provided callback.
- **The wrapped argument type** — the user's callback interface that must be wrapped in a tracing wrapper (e.g. `io.reactivex.rxjava3.core.Observer`, `java.lang.Runnable`).
- **The context store key class** — the FQN used to key the `contextStore` (almost always the boundary type itself).
- **The tracing wrapper** — a new class implementing the callback interface that reattaches the captured context before delegating (e.g. `TracingObserver` implements `Observer`).
- **The wrapper's methods to guard** — the callback methods that must reattach context before running user code (e.g. `onNext`, `onError`, `onComplete` for RxJava observers).

## Tests for context-tracking instrumentations

Context-tracking tests assert that **a span created by user code inside the wrapped callback becomes a child of the parent span active when the boundary was created**:

```java
runUnderTrace("parent", () -> {
    constructBoundary()                                  // capture happens here
        .subscribe(item -> userCodeStartsAChildSpan());  // restore happens around the lambda
});
// Assert: 1 trace, 2 spans — child has parent's spanId as parentId.
```

Do NOT assert span kinds, operation names, span tags, or span types on the *target* methods — there are no spans on those methods.

## Choosing between method overloads

When a boundary type exposes multiple overloads of the subscribe / invoke method — e.g. `Flowable.subscribe(Subscriber)` vs `Flowable.subscribe(FlowableSubscriber)`, or `Mono.subscribe(Subscriber)` vs `Mono.subscribe(CoreSubscriber)` — hook the **most specific framework-internal interface**, not the public wrapper.

**Why:** the public overload typically delegates to the internal one. Hooking the public overload causes double-wrapping (every subscription flows through the wrapper twice) and the internal overload sees a wrapped argument of the wrong runtime type.

**How to identify the right overload:** read the framework source. The public method usually calls a `subscribeActual(...)` or similar protected method that takes the framework-internal interface. If the framework documents one of the overloads as "for internal use only" or marks it `public final`, that's the implementation method — hook it.

**Reference:** dd-trace-java's `rxjava-2.0` hooks `subscribe(Observer)` (the implementation).

## When NOT to write a context-tracking instrumentation

If the library DOES perform I/O — sends HTTP requests, runs DB queries, makes RPC calls, talks to a broker, reads/writes a cache — write a **span-creating instrumentation** (`InstrumenterModule.Tracing`) instead. Context-tracking is only for libraries that coordinate work; the moment there's actual I/O to observe, you want spans around it.

Hybrid libraries that BOTH coordinate work AND perform I/O usually get one span-creating instrumentation for the I/O path and (optionally) one context-tracking instrumentation for the coordination path. `lettuce-5.0` is an example: there is a span-creating instrumentation for Redis commands and a separate context-tracking instrumentation for the async command queue.
