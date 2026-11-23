# Performance

Allocations and CPU cost on hot paths (span creation and finish, tagging, propagation, instrumentation callbacks), at startup, and in tooling.

This lens does not keep its own rubric. The tracer performance rubric is owned by the [perf-review skill](../../perf-review/SKILL.md) and its [references](../../perf-review/references/); read and apply those instead of restating them here. Add a rule below only for a repo-specific performance concern that `perf-review` does not already cover.

**When planning:** take the `perf-review` rubric into account when designing the change.

**When reviewing:** apply the `perf-review` rubric to the frozen diff, including its "assume hot" posture and its noise limits. Report a finding when the diff matches a rubric check on a hot, startup, or high-volume path. Cite the rubric check ID (or rule ID, if a rule is added below) and say which path it is on. Report in this skill's severity scale, not `perf-review`'s own format. The noise limits apply to rubric checks only: report a diff that matches a `PERF-*` rule's `Flag:` line, even when the cost looks small.

## 1. Allocation and retention

### PERF-1.1: Use the context-object `TagMap.forEach(thisObj, BiConsumer)` overload instead of a capturing lambda
- Flag: `tags.forEach(entry -> ...)` on a per-span path where the lambda captures a local or `this`.
- ❌ `tags.forEach(e -> writer.write(e));`
- ✅ `tags.forEach(writer, (w, e) -> w.write(e));`

### PERF-1.2: Cache UTF-8 encodings of known low-cardinality values instead of encoding per record
- Flag: `getBytes(UTF_8)`/`UTF8BytesString.create(...)` per span or log record for a value from a small fixed set (severity text, event name, span kind).
- ❌ `out.write(severityText.getBytes(UTF_8));`
- ✅ `private static final UTF8BytesString ERROR = UTF8BytesString.create("ERROR");` and look up the constant.

### PERF-1.3: Compile a rarely used `Pattern` on demand instead of holding it in a static or lazy holder
- Flag: a `static final Pattern` or lazy-holder class for a regex used once or only on a rare path (startup parsing, an opt-in feature), so it stays live for the whole process.
- Not when: the pattern runs per call on a hot path; then compile once (`perf-review` check #2).

### PERF-1.4: Don't add `WeakReference`/`SoftReference`-based caches on tracer paths without a load benchmark
- Flag: a new class extending or holding `java.lang.ref.Reference` to cache or reuse objects; references add GC work, especially under G1.
- ✅ A plain allocation of a small short-lived object, unless a multi-threaded JMH benchmark shows the cache pays off.

### PERF-1.5: Attach per-object state with `InstrumentationContext`/`ContextStore`, not a static map evicted on an observed close
- Flag: a `static` `Map`/`ConcurrentHashMap` keyed by a library object (connection, request, channel), whose only eviction is a matching `close()`/completion callback.
- Not when: the key type can't be a context-store key (not matched by the instrumentation); then use a weak-keyed map.
- ❌ `static final Map<ConnectionInfo, Options> OPTIONS = new ConcurrentHashMap<>();`
- ✅ `InstrumentationContext.get(Connection.class, Options.class).put(connection, options);`

### PERF-1.6: Cache per-class type checks in advice with `instanceof`/`ClassValue`, not per-call name or interface walking
- Flag: `getClass().getName().startsWith(...)`, `getInterfaces()` loops or other reflection on every advised call to decide whether an argument is a library type.
- ❌ `if (arg.getClass().getName().startsWith("io.vertx.") && implementsHandler(arg.getClass()))`
- ✅ `arg instanceof Handler`, or a `ClassValue<Boolean>` when the type isn't on the advice classpath.

## 2. Concurrency and background work

### PERF-2.1: Use `Queues.mpscArrayQueue(...)` instead of `ArrayBlockingQueue` on multi-producer emission paths
- Flag: an `ArrayBlockingQueue`/`LinkedBlockingQueue` that application threads offer to per span, log or event, and that a single background thread drains. This includes an existing queue whose declaration or capacity the diff changes.
- ✅ `datadog.common.queue.Queues.mpscArrayQueue(capacity)` (or `mpscBlockingConsumerArrayQueue` when the consumer must block).

### PERF-2.2: Don't schedule periodic tasks or threads when the feature has nothing to send
- Flag: `start()` scheduling an exporter/flush task before checking that a sender, endpoint or supported protocol exists.
- ✅ `if (sender == null) { return; }` before scheduling.

### PERF-2.3: Use an abstract class with a separate `final` no-op subclass instead of an interface with a lambda `NOOP` constant
- Flag: a new interface on a hot or widely used path that declares `Foo NOOP = () -> ...;`, which blocks class-hierarchy-analysis inlining and allocates the lambda even when the feature is off.
- ❌ `public interface OtlpLogsCollector { OtlpLogsCollector NOOP_COLLECTOR = () -> OtlpPayload.EMPTY; }`
- ✅ `public abstract class OtlpLogsCollector` plus `final class NoopLogsCollector { static final NoopLogsCollector INSTANCE = ...; }`

## 3. Benchmarks

### PERF-3.1: Run JMH benchmarks of shared structures with several threads by default
- Flag: a new JMH benchmark for a cache, map, queue or context store with no `@Threads` (defaults to 1), which hides contention and allocation effects.
- ✅ `@Threads(8)` (overridable on the command line for baseline runs)

## Don't flag
- A linear scan over a small, fixed-size array on a hot path instead of a keyed set; for a handful of entries the scan is as cheap as a lookup.
