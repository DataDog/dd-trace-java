# Design

Where code belongs, who owns state, and API and type shape.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line and no `Not when:` applies, or when it has a clear problem in this lens's area that no rule covers (mark it `no rule`). Cite the rule ID and propose the concrete alternative.

## 1. Module boundaries

### DES-1.1: Keep a product's `-api` module free of `:internal-api` and tracer types
- Flag: `compileOnly(project(":internal-api"))` (or `AgentTracer`/`AgentSpan`/`TraceInterceptor` imports) added to a published product API module; move tracer-dependent code into the product's `-lib` module. See [ARCHITECTURE.md](../../../../ARCHITECTURE.md#products).
- ❌ `compileOnly(project(":internal-api")) // in a {product}-api build.gradle.kts`

### DES-1.2: Put library-specific shared code in the integration's common module or a helper, not `agent-bootstrap` or `dd-trace-core`
- Flag: a new class under `agent-bootstrap/.../decorator` or `dd-trace-core` that only one library family or one instrumentation uses; share it through that integration's common module (e.g. `aws-java-common`) or make it an instrumentation helper.
- ❌ `package datadog.trace.bootstrap.instrumentation.decorator; class AwsAccountIdentity`

### DES-1.3: Don't make bootstrap or telemetry modules depend on a product module
- Flag: a new `project(":products:...")` or metrics-API dependency in a `-bootstrap` or `telemetry` build file; keep the product's metric collector in `internal-api` like `DebuggerMetricCollector`/`IastMetricCollector`.

### DES-1.4: Keep special cases for one format, library or subsystem in the component that owns it, not in shared code
- Flag: a shared class (`ContextInterpreter`, a shared decorator, `CoreTracer`, a common instrumentation helper) gains a branch, constant or field that serves only one propagation format, library, product or context manager. Examples: Haystack reserved baggage handled in `ContextInterpreter` instead of `HaystackHttpCodec`; legacy-context-manager or profiler coupling written out in an instrumentation instead of behind the bootstrap API.
- Not when: the shared class already dispatches over every variant by design (for example, a `switch` over all propagation styles).
- ❌ `if (HAYSTACK.equals(style) && isReservedKey(key)) { return; } // in ContextInterpreter`
- ✅ the reserved-key check inside `HaystackHttpCodec`'s extractor

### DES-1.5: Don't add new uses of OkHttp/Okio utilities in tracer core code
- Flag: new `okio.*`/`okhttp3.*` imports outside the existing transport/writer classes (e.g. `Utf8.size(...)` for a size limit); the dependency is slated for removal, so use a JDK or approximate alternative.

## 2. State ownership and encapsulation

### DES-2.1: Keep cached, shared value objects immutable; put per-instance mutable state in its own holder
- Flag: a `volatile` or setter-mutated field added to a type cached and shared across callers (e.g. `DBInfo`, cached per URL in `JDBCConnectionUrlParser`); set everything at construction, and hold per-connection state elsewhere.
- ❌ `private volatile String oracleServiceAction; // in DBInfo`

### DES-2.2: Fix callers that pass `null` instead of normalizing it in a shared API
- Flag: `if (parentContext == null) { parentContext = root(); }` (or similar) added to a shared decorator/API method whose contract is non-null; fix the instrumentation that passes `null`.

### DES-2.3: Expose operations on the owning type instead of opening internals
- Flag: private fields widened to package-private to avoid synthetic accessors, callers reaching into nested internals (`hashtable.sizeManager.tryReserve()`), or factories placed on a nested `State` class rather than the owning type (`ConcurrentHashtable.createBounded`). Move the code next to the data or add a proper API.

### DES-2.4: Normalize special values once, in the factory that creates them
- Flag: the same special-case check (`id == 0 ? ZERO : ...`) repeated across several public factory methods (`DDTraceId.from`/`fromHex`), which also tends to miss a variant (128-bit ids); let the underlying factory (`DD64bTraceId.from`) return the constant.

## 3. API and type shape

### DES-3.1: Add methods to public `dd-trace-api` interfaces as `default` methods
- Flag: a new abstract method on a public interface in `dd-trace-api` (or another published API); it breaks source and binary compatibility for implementers.
- ✅ `default void annotatePrompt(LLMObs.Prompt prompt) {} // LLMObsSpan`

### DES-3.2: Don't widen shared interfaces with implementation state or internals
- Flag: a new method on `AgentSpan`, `ProfilingContextIntegration` or another cross-module interface that exposes internal state (`isFinished()`) or a product internal (`clearContext()`) without a demonstrated need; reuse existing semantics instead.
- ❌ `default void clearContext() {}`
- ✅ `profiling.setContext(Context.root());`

### DES-3.3: Capture `Context`, not a span alongside a `Context`, when migrating instrumentation
- Flag: an instrumentation storing both a span and a `Context` under separate keys (`CONNECT_SPAN`, `CONNECT_CONTEXT`); migrate it to store the `Context` and derive the span with `AgentSpan.fromContext`.
- ❌ `context.put(CONNECT_SPAN, span).put(CONNECT_CONTEXT, Context.current())`

### DES-3.4: Give a test annotation one condition mechanism, with the same semantics in Spock and JUnit
- Flag: an annotation like `@Flaky` that accepts two parallel ways to express a condition, or whose Spock and JUnit extensions interpret the same attribute differently (simple vs fully qualified class names).

## 4. Configuration ownership

### DES-4.1: Generate config-derived lists from `metadata/supported-configurations.json`
- Flag: a hand-maintained list of config keys (sensitive keys, filters) plus a drift-guard test that parses the metadata file; extend the build-time generation that produces `GeneratedSupportedConfigurations` instead.
- ✅ `SENSITIVE_KEYS.contains(key)`

### DES-4.2: Reuse the config telemetry pipeline for origins instead of tracking them separately
- Flag: new code recording where a setting came from (env, file, marker) alongside the existing configuration telemetry reporting. See [adding a new configuration](../../../../docs/add_new_configurations.md).

### DES-4.3: Keep the old mechanism behind a config flag when replacing one that changes resource or route naming
- Flag: an instrumentation change that removes the previous hook (e.g. moving from `onRefresh` to `getHandler`) where user-visible names could change, with no way to restore the old behaviour.

## 5. Propagation and export

### DES-5.1: Add payload- or transport-level metadata in the exporter, not as a tag on every span
- Flag: a span tag set on every span for something that is constant per payload (SDK semantics mode, stats-computed marker); emit it once in the serializer/exporter (e.g. as an OTLP resource attribute).

### DES-5.2: Don't put application-supplied, unbounded values in shared `_dd.p.*` propagation tags
- Flag: new `_dd.p.*` tags carrying app-controlled strings (app name, session id); they share the `x-datadog-tags` size cap, and exceeding it drops the whole header (including `_dd.p.tid`). Consider baggage instead.

## Don't flag
- A missing `@FunctionalInterface` on an interface whose single abstract method is incidental.
- A `static final` mode constant read on a hot path (e.g. per virtual-thread mount), even though its tests must be forked.
- Deprecated delegating shim methods added to `TracerAPI` to support an in-progress API migration.
- Suggestions to rename or shorten propagation tags that are already GA (for example, to save header space). Their names are a fixed cross-SDK wire contract. A diff that does rename a GA tag is a finding under XSDK-3.2.
- Finer-grained Gradle convention plugins than one per high-level module role.

