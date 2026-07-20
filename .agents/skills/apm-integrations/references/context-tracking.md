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

**`dd-java-agent/instrumentation/rxjava/rxjava-2.0/`** — the canonical context-tracking module. It uses `InstrumenterModule.ContextTracking`. Contents: 5 type-instrumenters (`ObservableInstrumentation`, `FlowableInstrumentation`, `SingleInstrumentation`, `MaybeInstrumentation`, `CompletableInstrumentation`), 5 wrapper classes (`TracingObserver`, `TracingSubscriber`, `TracingSingleObserver`, `TracingMaybeObserver`, `TracingCompletableObserver`), 1 `RxJavaModule.java`, 1 `RxJavaAsyncResultExtension.java`. Read this before writing a new context-tracking module.

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

```groovy
runUnderTrace("parent") {
    constructBoundary()                                    // capture happens here
        .subscribe({ item -> userCodeStartsAChildSpan() }) // restore happens around the closure
}
// Assert: 1 trace, 2 spans — child has parent's spanId as parentId.
```

Do NOT assert span kinds, operation names, span tags, or span types on the *target* methods — there are no spans on those methods.

## Choosing between method overloads

When a boundary type exposes multiple overloads of the subscribe / invoke method — e.g. `Flowable.subscribe(Subscriber)` vs `Flowable.subscribe(FlowableSubscriber)`, or `Mono.subscribe(Subscriber)` vs `Mono.subscribe(CoreSubscriber)` — hook the **most specific framework-internal interface**, not the public wrapper.

**Why:** the public overload typically delegates to the internal one. Hooking the public overload causes double-wrapping (every subscription flows through the wrapper twice) and the internal overload sees a wrapped argument of the wrong runtime type.

**How to identify the right overload:** read the framework source. The public method usually calls a `subscribeActual(...)` or similar protected method that takes the framework-internal interface. If the framework documents one of the overloads as "for internal use only" or marks it `public final`, that's the implementation method — hook it.

**Reference:** dd-trace-java's `rxjava-2.0` module hooks the single-argument `subscribe(...)` method (matcher: `named("subscribe").and(takesArguments(1))`) with the argument typed as the base callback interface (e.g. `Observer` for `Observable`, `Subscriber` for `Flowable`). Read the module source to see the exact matcher — pattern-match on this rather than copying overload names.

## Library-native context maps — the `*ContextBridge` pattern

Some libraries expose their own request-scoped context map that users write to explicitly. Examples:

- **Reactor** — `reactor.util.context.Context` (immutable per-subscription map); users pass a span via `.contextWrite(ctx -> ctx.put("dd.span", span))`.
- **Kotlin coroutines** — `CoroutineContext` with custom `CoroutineContextElement`.
- **JAX-RS** — `ContainerRequestContext` for per-request state.
- **Vert.x** — `Context.putLocal(...)`.

When a library has a first-class context-map concept, the observer/subscriber wrapping pattern documented above is **not sufficient by itself** — it only handles the "capture the currently-active trace context at subscribe time" case. It does NOT handle the case where a user has placed a span in the library's own context map and expects that span to propagate through the operator chain.

**A context-tracking module for such a library MUST include a `*ContextBridge`-style helper** that:

1. Reads a well-known key (Datadog convention: `"dd.span"`) from the library-native context.
2. Adapts the retrieved object (which implements `WithAgentSpan` or is an `AgentSpan`) into a `datadog.context.Context`.
3. Stores that context in the toolkit-native `ContextStore` keyed by the library's reactive-streams primitives (`Publisher`, `Subscriber`).
4. Activates the stored context at the right lifecycle boundaries: on subscribe, on signal delivery, on blocking, and on internal subscriber handoff (e.g. Reactor's fused-operator path).

**Reference:** `dd-java-agent/instrumentation/reactor-core-3.1/` — master has `ReactorContextBridge.java` plus four supporting instrumentations that hook the specific lifecycle points: `BlockingPublisherInstrumentation` (for `.block()` / `.blockFirst()` / `.blockLast()` on the calling thread), `ContextWritingSubscriberInstrumentation` (for `.contextWrite(...)` subscribers at subscribe time), `CorePublisherInstrumentation` (for the base publisher interface handing off to downstream subscribers), and `OptimizableOperatorInstrumentation` (for Reactor's internal fused-operator optimization path). Regenerating this module without preserving these classes silently breaks downstream libraries — Spring WebFlux, Spring Kafka reactive `@KafkaListener suspend fun`, resilience4j-reactor, reactor-netty — that rely on the `dd.span` propagation path.

**How to detect the pattern in an unfamiliar library:** search the library's public API for a `Context` type with `put`/`get`/`hasKey` methods that user code can write to (as opposed to a Datadog-internal context). If such a type exists, the library has a native context map and needs a `*ContextBridge` helper.

**When regenerating an existing reactive module:** enumerate every `*Instrumentation.java` in the master module and account for each one. Dropping any without a documented reason is always a Rule #2 (regen-preservation) violation. Also preserve the master's `*ContextBridge`-style helper verbatim, or produce equivalent semantics — do not drop it in favor of the subscriber-wrapping pattern alone.

## Wrap placement and context-store lifecycle — memory considerations

Context-tracking instrumentations allocate two kinds of long-lived state that add up on hot reactive paths:

- **Wrapper instances** — one `TracingObserver`/`TracingSubscriber`/etc. per subscribe call.
- **Context-store entries** — one `ContextStore.put(subscriber, context)` per subscribe call, held until the subscription completes/cancels.

Place both at the narrowest scope that preserves correctness:

- **Wrap only at chain boundaries** — subscribe, `.contextWrite(...)`, `.block()` — not at every internal operator. Operator-level wrapping causes N-fold allocations for an N-operator chain and does not add propagation coverage that boundary wrapping doesn't already provide.
- **Use Subscriber-lifecycle context stores** — the store should drop entries when the subscription completes or cancels. `ContextStore` implementations in dd-trace-java use field injection on the key instance when possible (the common fast path), or a weak-map fallback when field injection is unavailable. In either case, choose a key with a bounded lifetime: use the subscriber (scoped to one subscription) rather than the publisher (which may be shared and long-lived across many subscriptions).
- **Avoid double-wrapping** — when a downstream operator already carries the context via the library-native context map (e.g., Reactor's `Context` flows through the whole operator chain by construction), do not add a per-operator wrap on top.

**Why this matters:** a 10-operator Reactor chain with per-operator wrapping allocates 10× the wrappers of a boundary-only implementation, and every allocation must be reclaimed when the subscription completes. In steady-state reactive services, that's the difference between context-tracking being invisible in profiling and being a measurable overhead.

## When NOT to write a context-tracking instrumentation

If the library DOES perform I/O — sends HTTP requests, runs DB queries, makes RPC calls, talks to a broker, reads/writes a cache — write a **span-creating instrumentation** (`InstrumenterModule.Tracing`) instead. Context-tracking is only for libraries that coordinate work; the moment there's actual I/O to observe, you want spans around it.

Hybrid libraries that BOTH coordinate work AND perform I/O usually get one span-creating instrumentation for the I/O path and (optionally) one context-tracking instrumentation for the coordination path. `lettuce-5.0` is an example: there is a span-creating instrumentation for Redis commands and a separate context-tracking instrumentation for the async command queue.

## Preserving cancellation on `CompletableFuture` / `CompletionStage` returns

When advice attaches a completion callback to a `CompletableFuture` returned from an async client, do NOT reassign the return with `future = future.whenComplete(...)`. `whenComplete` produces a **dependent stage**; cancelling that stage does not cancel the original request. The caller's `future.cancel(true)` then only cancels the dependent stage and leaves the underlying I/O running.

The correct pattern attaches the callback for side-effects only, without reassigning the return — so `@Advice.Return` does NOT need `readOnly = false`. It also declares `onThrowable = Throwable.class` so the exit runs even when the instrumented method throws before returning its future (otherwise ByteBuddy skips exit advice on thrown paths and any span/scope started on enter leaks). And per the "no lambdas in advice methods" rule in `advice-class.md`, the completion callback must be a named helper class, not a lambda — lambdas compile to synthetic classes that muzzle does not helper-inject and ByteBuddy cannot resolve at the instrumentation site.

```java
// WRONG — three issues in one:
//   (a) reassigning `future = ...` severs cancellation from the caller
//   (b) unnecessary readOnly = false
//   (c) lambda body compiles to a synthetic class that isn't helper-injected
@Advice.OnMethodExit(suppress = Throwable.class)
public static void exit(@Advice.Return(readOnly = false) CompletableFuture<Response> future,
                        @Advice.Enter AgentSpan span) {
  future = future.whenComplete((result, error) -> finishSpan(span, result, error));
}

// CORRECT — attach a named callback for its side-effect; keep the return read-only;
// run on both normal and throwable exit so the caller-thrown case still cleans up.
@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
public static void exit(@Advice.Return CompletableFuture<Response> future,
                        @Advice.Enter AgentSpan span,
                        @Advice.Thrown Throwable thrown) {
  if (thrown != null) {
    // The instrumented method threw before returning a future — no future to attach to.
    // Finish the span here directly.
    ClientCompletionCallback.finishOnThrow(span, thrown);
    return;
  }
  if (future != null) {
    future.whenComplete(new ClientCompletionCallback(span));
  }
}
```

where `ClientCompletionCallback` is a named `BiConsumer<Response, Throwable>` in a separate helper file listed in `helperClassNames()`:

```java
public final class ClientCompletionCallback implements BiConsumer<Response, Throwable> {
  private final AgentSpan span;

  public ClientCompletionCallback(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(Response result, Throwable error) {
    // finish the span with the observed outcome
  }

  public static void finishOnThrow(AgentSpan span, Throwable thrown) {
    // handle the enter-but-no-future case
  }
}
```

Only add `readOnly = false` if you have a documented reason to substitute the return value. If your goal is just to observe completion, the read-only + named-callback pattern is safer (preserves cancellation), obeys the no-lambdas-in-advice rule, and handles the thrown-before-return case.

If the wrapper genuinely needs to return a different `CompletionStage` (rare), forward `cancel(...)` to the original future explicitly.
