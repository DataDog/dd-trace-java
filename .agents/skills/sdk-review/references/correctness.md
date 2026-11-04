# Correctness

Bugs, races, unhandled edge cases, and tests that pass when they shouldn't.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line and no `Not when:` applies. Cite the rule ID, location, and the failing input or interleaving.

## 1. Values and edge cases

### COR-1.1: Read user-settable tags through a typed accessor or an `instanceof` guard, not a blind cast
- Flag: `(CharSequence) span.getTag(...)`, or a cast of a value from `getTag`, a request attribute or a `ContextStore` that is only preceded by a `!= null` check.
- ❌ `spanKind((CharSequence) span.getTag(SPAN_KIND))`
- ✅ `spanKind(span.getSpanKindString())` or `if (o instanceof ContextContinuation) { ... }`

### COR-1.2: Compare `DDTraceId` values with `equals()`, never `==`
- Flag: `==` or `!=` between `DDTraceId`s (`getTraceId() != ...getTraceId()`); only `DDTraceId.ZERO` is interned, so equal ids are usually distinct instances.
- ❌ `if (parent.getTraceId() != span.getTraceId())`
- ✅ `if (!parent.getTraceId().equals(span.getTraceId()))`

### COR-1.3: Range-check numbers from libraries or config before indexing with them or using them as limits
- Flag: an extracted HTTP status passed to `Config.get().getHttpServerErrorStatuses().get(status)` (a `BitSet`, throws on a negative index) without a `100..599` guard, or a parsed limit (max items/bytes) left negative instead of clamped to `>= 0` at parse time.
- ❌ `span.setError(Config.get().getHttpServerErrorStatuses().get(status));`
- ✅ `if (status >= 100 && status <= 599) { span.setError(SERVER_ERROR_STATUSES.get(status)); }`

### COR-1.4: Subtract the old entry's contribution when replacing a key in a size- or count-tracked map
- Flag: a running total (`size += key.length() + value.length()`) or item count updated on `put` without checking `containsKey` and removing the previous value's size.

### COR-1.5: Sort `File.listFiles()` / `File.list()` results before relying on their order
- Flag: their output feeding an ordered result (`LinkedHashSet`, generated code, helper order); the order is filesystem-dependent and breaks reproducible builds.
- ✅ `File[] siblings = dir.listFiles(); Arrays.sort(siblings);`

### COR-1.6: Don't pass `Instant.now()`-derived start timestamps to `startSpan(..., startTimeMicros)`
- Flag: an explicit start time from `Instant.now()` or `System.nanoTime()`; explicit-start spans finish on the millisecond `SystemTimeSource`, so durations come out short or negative. Use the same clock for both ends.

### COR-1.7: Null-check components that configuration can disable before calling them
- Flag: a call on an optional cache, collector or writer field that is `null` when its feature is turned off, with no guard on that path.

## 2. Concurrency and ordering

### COR-2.1: Publish fields that readers combine as one atomic update
- Flag: two related fields (e.g. sampling priority, then the OTel tracestate derived from it) written in separate steps while another thread can read both and cache a value built from the intermediate state.

### COR-2.2: Publish state before the call that can first consume it
- Flag: a holder set after `installOn(inst)` (which can trigger retransformation), or per-thread state seeded after the first mount/callback that reads it; a miss in that window is usually permanent. Register it in a pre-install hook (e.g. ByteBuddy `onBeforeInstall`) and test that ordering.
- ❌ `ClassFileTransformer t = builder.installOn(inst); Holder.set(t);`

## 3. Conditions and configuration

### COR-3.1: Gate on the condition that actually decides the behaviour, not a proxy for it
- Flag: `spanContext == null` used to mean "local root" (propagation behaviour `restart`/`ignore` discards a non-null context), a generic marker interface used to mean "this specific context store is present", or a snapshot-wide flag used for a per-span decision.
- ❌ `if (spanContext == null) { forceDrop(span); }`
- ✅ `if (span.getLocalRootSpan() == span) { forceDrop(span); }`

### COR-3.2: Read a setting from the same source, with the same gating, as the code it pairs with
- Flag: a static `Config.get().isXEnabled()` where the matching call sites use the remote-config-aware `traceConfig().isXEnabled()`, or a read moved into `InstrumenterConfig` that now lands inside the `Platform.isNativeImageBuilder()` carve-out and silently disables the feature on native image.

### COR-3.3: Use `check()` or `throw GradleException(...)` for build checks in Gradle Kotlin DSL, not `assert`
- Flag: `assert(...)` in a `.gradle.kts` file or Kotlin build logic; Kotlin `assert` only runs with `-ea`, unlike Groovy `assert`, so the check never fires.
- ❌ `assert(jarSize > MIN_SIZE)`
- ✅ `check(jarSize > MIN_SIZE) { "Jar is unexpectedly small: $jarSize" }`

## 4. Resources and lifecycle

### COR-4.1: Clear pending span or scope fields in `finally`
- Flag: a field such as `pendingQueueSpanToFinish` finished and nulled on the happy path only, so an exception leaves a stale reference that is finished later or twice.

### COR-4.2: Isolate failures per element in loops over recorders, listeners or callbacks
- Flag: a new loop over pluggable recorders/listeners with no `try`/`catch (Throwable)` + log, where the equivalent sibling loop has one.

### COR-4.3: Resolve every continuation exactly once, and only from its owner
- Flag: a `ContextContinuation` with a path (cancel, early return, exception) that neither resumes nor releases it, a resumed `ContextScope` that is never closed, or a child/scoped task releasing a continuation it shares with its caller but doesn't own. See [how instrumentations work](../../../../docs/how_instrumentations_work.md#continuations).

### COR-4.4: Call decorators and register async callbacks before `scope.close()`, then `span.finish()`
- Flag: `scope.close()` before `DECORATE.beforeFinish(...)`/`onError(...)` or before callback registration (`CompletableFuture.whenComplete`, listeners), or `span.finish()` before `scope.close()`. See [instrumentation design guidelines](../../../../docs/instrumentation_design_guidelines.md#2-scope-lifecycle-order).
- ✅ `DECORATE.beforeFinish(span); registerCallback(span); scope.close(); span.finish();`

### COR-4.5: Catch `Throwable` around `Instrumentation.retransformClasses` and similar JVM calls on background threads
- Flag: `retransformClasses`/`redefineClasses` on a telemetry, remote-config or scheduler thread with only `catch (Exception)`; an `InternalError` or `LinkageError` kills the thread.

## 5. Instrumentation and bootstrap

### COR-5.1: Never load `java.util.logging`, `java.nio.file` or `javax.management` from premain code
- Flag: these imports, `FileSystems.getDefault()`, `Path.of`/`Paths.get` or `Files.*` on premain paths (`agent-bootstrap`, `AgentPreCheck`); use `java.io.File` and the shaded `org.slf4j` logger, or `System.err` (never `System.out`) before the logger is configured. See [bootstrap design guidelines](../../../../docs/bootstrap_design_guidelines.md).
- ❌ `Files.readAllBytes(Paths.get(path))`
- ✅ `new FileInputStream(new File(path))`

### COR-5.2: Use `Java8BytecodeBridge` instead of static interface methods in advice
- Flag: `Context.current()`, `Context.root()` or any other static interface method call inside an `@Advice.OnMethodEnter`/`@Advice.OnMethodExit` method; it can cause a `VerifyError` once inlined. See [instrumentation design guidelines](../../../../docs/instrumentation_design_guidelines.md#3-static-interface-methods-in-advice).
- ❌ `Context ctx = Context.current();`
- ✅ `Context ctx = currentContext(); // static import from Java8BytecodeBridge`

### COR-5.3: Keep advice self-contained: no calls into the advice or instrumentation class
- Flag: advice calling another method or reading a field (including a `Logger`) of its own advice class or the instrumentation class, or code in an advice constructor; only the advice method bytecode is copied. Move the code into a helper or bootstrap class. See [how instrumentations work](../../../../docs/how_instrumentations_work.md#advice-classes).

### COR-5.4: Keep `helperClassNames()` complete, and preload helpers used on virtual-thread paths
- Flag: a manual `helperClassNames()` missing a new helper or its nested/enum classes (a non-empty list is authoritative), generated helpers overriding a parent instrumentation's list, or advice on a park/mount path referencing a class that only another, separately disableable integration lists in `preloadClassNames()`. See [how instrumentations work](../../../../docs/how_instrumentations_work.md#helper-classes).

### COR-5.5: Use `suppress = Throwable.class` on advice, and no `onThrowable` on constructor exit advice
- Flag: `@Advice.OnMethodEnter`/`OnMethodExit` without `suppress = Throwable.class` and no stated reason; `onThrowable` on exit advice for a constructor (`@Advice.This` is partially initialized); a `doOnError`/`doBeforeFinish` override that can throw anything but `BlockingException`. See [how instrumentations work](../../../../docs/how_instrumentations_work.md#decorators-must-not-throw).

### COR-5.6: Null-check `ContextStore` keys, and call `InstrumentationContext.get()` only from advice
- Flag: `store.put(key, ...)`/`store.get(key)` where `key` can be null, or `InstrumentationContext.get(...)` in a helper or decorator (pass the `ContextStore` in instead). See [how instrumentations work](../../../../docs/how_instrumentations_work.md).

### COR-5.7: Make ASM rewrites of third-party methods fail loudly when the expected shape is missing
- Flag: a `MethodVisitor` that rewrites the first N matching call sites (`if (rewritten < 2)`) or silently does nothing when none match; count matches and throw in `visitEnd` unless the count is exactly as expected, and cover it in a latest-dep test.

### COR-5.8: Pass the instrumentation name, not the operation name, as `startSpan`'s first argument
- Flag: `startSpan(OPERATION_NAME, ...)` or `startSpan(SPAN_NAME, SPAN_NAME)`, so the span is attributed to the wrong integration.
- ❌ `startSpan("grpc.server", GRPC_SERVER)`
- ✅ `startSpan(INSTRUMENTATION_NAME, GRPC_SERVER)` (the name passed to the module's `super(...)`)

## 6. Tests

### COR-6.1: Reset every static field a test mutates in `@AfterEach` / `cleanup()`
- Flag: a test calling a static setter (`BaseHash.updateBaseHash(42L)`, `DataStreamsTags.setServiceNameOverride(...)`) without restoring it, or a cleanup that resets only some of the fields touched; JUnit doesn't guarantee method order, so a later test can pass on stale state.

### COR-6.2: Put `@Flaky` on the class when the class has expensive shared setup
- Flag: method-level `@Flaky` in a class whose `@RegisterExtension`/`@BeforeAll` starts a container or smoke app; `-PskipFlakyTests` still runs that setup before disabling the method.

### COR-6.3: Make each test case reach the code path its name claims
- Flag: a parameterized row or migrated test whose input no longer exercises the branch it names (an "out of range" id set to `MAX - 1`, a "fallback" case the code never takes), with no assertion on an observable effect (log output, tag, counter) that proves the branch ran.
- Also flag: a fix to a shared parser or code path that is tested for only one of its inputs (one URL scheme), or a smoke test narrowed to a single case.

## Don't flag
- A missing null check on `AgentSpan.fromContext(...)` when the context was captured from a span and is known to contain one.
- `catch (Throwable)` around reflection or decorator hooks; it is the established exception-barrier idiom in `BaseDecorator`/`HttpServerDecorator`.
- Clearing a thread's interrupt status after `InterruptedException` when it is deliberate and commented (e.g. so later polls don't fail immediately).
- A JVM-exit shutdown path that skips a lifecycle lock held by app threads, when the calls it makes are idempotent.
- An approximate size (`String.length()`) used for buffer thresholds instead of an exact UTF-8 byte count.
- A transformer re-entrancy guard that deliberately fails open (skips nested work).
- Matching JDK lambdas by the `$$Lambda` class-name prefix where the Java 8 baseline rules out nest-host checks.
