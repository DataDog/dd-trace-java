# InstrumenterModule Guidance

> Referenced from `SKILL.md` Step 5. Everything needed to write the `InstrumenterModule` class correctly.

## Conventions to enforce

- Add `@AutoService(InstrumenterModule.class)` annotation — required for auto-discovery
- Extend the correct `InstrumenterModule.*` subclass (never the bare abstract class)
- Implement the **narrowest** `Instrumenter` interface possible:
  - Prefer `ForSingleType` > `ForKnownTypes` > `ForTypeHierarchy`
  - **EXCEPTION — API specification / interface-only libraries**: when the target library is a specification JAR containing only interfaces (no concrete classes), `ForSingleType` does not work because there are no concrete types to instrument directly. You MUST use `ForTypeHierarchy` with `implementsInterface(named("the.interface.Fqn"))`. This is how vendor implementations of the specification (ActiveMQ, IBM MQ, EclipseLink, Hibernate, etc.) get instrumented through the common interface contract.
  - Common API JARs that REQUIRE `ForTypeHierarchy` + `implementsInterface`:
    - **JMS**: `javax.jms:javax.jms-api`, `jakarta.jms:jakarta.jms-api` — see `dd-java-agent/instrumentation/jms/javax-jms-1.1/` for the canonical example. Targets `MessageProducer`, `MessageConsumer`, `Message`, `MessageListener` interfaces.
    - **JPA**: `javax.persistence:javax.persistence-api`, `jakarta.persistence:jakarta.persistence-api`
    - **JDBC**: `java.sql.*` — interfaces like `Driver`, `Connection`, `Statement`, `PreparedStatement`
    - **JCache**: `javax.cache:cache-api`
    - **Bean Validation**: `jakarta.validation:jakarta.validation-api`
    - **JAX-RS**: `jakarta.ws.rs:jakarta.ws.rs-api`
    - **JAX-WS**: `jakarta.xml.ws:jakarta.xml.ws-api`
    - **Servlet**: `jakarta.servlet:jakarta.servlet-api`
  - **DO NOT classify interface-only API JARs as not_applicable.** They ARE instrumentable via `implementsInterface()`.
- Add `classLoaderMatcher()` if a sentinel class identifies the framework on the classpath
- Declare **all** helper class names in `helperClassNames()`:
  - Include inner classes (`Foo$Bar`), anonymous classes (`Foo$1`), and enum synthetic classes — for enums, each constant with an anonymous body generates its own synthetic class (`MyEnum$1`, `MyEnum$2`, …), each must be listed individually
- Declare `contextStore()` entries if context stores are needed (key class → value class)
- **Null-check before every `ContextStore` key** — `ContextStore` does not support null keys. Always guard with a null check before calling `store.put(obj, ...)` or `store.get(obj)`. Passing null throws at runtime; with `suppress = Throwable.class` this silently drops the span.
- Keep method matchers as narrow as possible (name, parameter types, visibility)

## Must NOT do in InstrumenterModule

- **Do not extract one-shot method return values into static constants.**
  Methods like `triggerClasses()`, `contextStore()`, `classLoaderMatcher()`, and `methodAdvice()`
  are called **once** by `AgentInstaller` / the framework wiring. Extracting their return value
  into a `private static final` constant provides no performance benefit and needlessly bloats
  the constant pool of the instrumentation class.

  ❌ `private static final String[] TRIGGER_CLASSES = new String[]{"com.example.Foo"};`
     `public String[] triggerClasses() { return TRIGGER_CLASSES; }`

  ✅ `public String[] triggerClasses() { return new String[]{"com.example.Foo"}; }`

### Before writing a new module, scan for an existing one

Before creating `dd-java-agent/instrumentation/$framework/$framework-$version/`, check whether `dd-java-agent/instrumentation/$framework/` already exists and what's in it.

If an existing module covers the same framework at a compatible version, **modify it in place** — do NOT create a parallel `$framework-2.0-generated/` or nested `$framework/$framework-2.0/` copy. Duplicate modules cause muzzle to match twice, double the CI cost, and create reviewer confusion (see PR #10941's "the more I read about it, the less I understand what was done" — a duplicate module that the reviewer could not disentangle from the original).

If the existing module targets a genuinely different version range (e.g. existing `foo-1.0/` and you're adding `foo-3.0/`), a version-sibling is correct — but confirm by reading the existing module's muzzle range first.

### Module constructor: choose names based on sibling structure

Each name passed to `super(...)` becomes a distinct `DD_TRACE_<NAME>_ENABLED` flag. Choose the number of names based on whether version-specific siblings exist (or are imminent):

**Single module, no version siblings, no imminent sibling planned** — pass ONE name:

```java
// CORRECT — single-module framework (freemarker lives in freemarker-2.3.9/
// and freemarker-2.3.24/ sibling directories yet still passes ONE name because
// the two directories share the same integration name)
public DollarVariableInstrumentation() {
    super("freemarker");
}
```

Adding a version alias here mints a `DD_TRACE_<NAME>_<VER>_ENABLED` flag that has no counterpart to gate against; it doubles the config surface for no operator benefit. Empirically, single-name-only frameworks in dd-trace-java include `freemarker` (across `freemarker-2.3.9/` and `freemarker-2.3.24/`), `liberty` (across `liberty-20.0/` and `liberty-23.0/`), and most other framework directories with a single integration name.

**Counter-example — `sparkjava`:** the `sparkjava-2.3/` module uses `super("sparkjava", "sparkjava-2.4")` (note the `-2.4`, not `-2.3`) because the module compiles against Spark 2.3 but tests against 2.4 (Spark's `JettyHandler` is available from 2.4). The versioned alias here reflects the version the code EXERCISES, not the compile-time minimum. This is intentional; do NOT invent a `-2.3` alias just because the directory is named `sparkjava-2.3/`. If in doubt, read the master `super(...)` and copy it verbatim.

**Multiple version siblings exist** (`okhttp-2.0/` AND `okhttp-3.0/`, `jedis-1.4/` AND `jedis-3.0/` AND `jedis-4.0/`) — pass a shared group name PLUS a version-qualified alias so each version has an independent toggle sharing one group flag:

```java
// CORRECT — okhttp has real siblings (okhttp-2.0 and okhttp-3.0)
public OkHttp3Instrumentation() {
    super("okhttp", "okhttp-3");
}
```

Users can then set `DD_TRACE_OKHTTP_ENABLED=false` (group off) OR `DD_TRACE_OKHTTP_3_ENABLED=false` (this version only).

**New module you expect will soon have a sibling** — add the alias upfront and document why in the commit message. If no sibling appears, drop the alias in a follow-up.

**Existing module** (modifying, refactoring, or splitting): read the existing module's `super(...)` and copy it verbatim. Integration names are public config API — renaming one silently breaks customer `DD_TRACE_*_ENABLED` settings.

Before choosing, list the `dd-java-agent/instrumentation/$framework/` directory — the contents are the ground truth for whether siblings exist.

Do NOT add version aliases to the decorator's `instrumentationNames()` — that method is for analytics keys only.

**When rewriting or refactoring an existing module, preserve every override the master version has.** Not just `super(...)` — also `defaultEnabled()`, `helperClassNames()`, `contextStore()`, `orderPriority()`, `muzzleDirective()`, and any other overridden method. Read the current file on master before writing; carry each override forward verbatim unless there's a documented reason to change it. Silent loss of `defaultEnabled() = false` (or similar opt-in flags) ships an integration with a different default than users expected.

**Concrete failure pattern (from dd-trace-java PR #11939, rxjava-3.0 regen):** master has

```java
public RxJavaModule() {
  super("rxjava", "rxjava-3");   // ← two args: family name + version alias
}
```

An eval regenerated this as

```java
public RxJavaModule() {
  super("rxjava");   // ❌ dropped "rxjava-3" version alias
}
```

Impact: customers who set `DD_TRACE_RXJAVA_3_ENABLED=false` to opt out silently lose that opt-out — the flag stops being recognized. No CI check catches it; the regression is only visible when a customer tries to disable the integration. **When regenerating a module that already exists, both the number of arguments AND the exact string values of `super(...)` must be preserved verbatim.**

### Package layout must be preserved verbatim on regen

When regenerating an existing module, use the exact same Java package for every production class. Master's package is authoritative; do not rename, consolidate, or shorten.

**Concrete failure pattern (from dd-trace-java PR #11940, reactor-core-3.1 regen):** master's production classes live under `datadog.trace.instrumentation.reactor.core`. An eval regenerated the module under `datadog.trace.instrumentation.reactorcore` (concatenated form, chosen by analogy with `datadog.trace.instrumentation.rxjava3`).

Consequences:
- Silently breaks fully-qualified class references from other modules. In this case, `dd-java-agent/instrumentation/graal/graal-native-image-20.0/` has a `NativeImageGeneratorRunnerInstrumentation` that lists `datadog.trace.instrumentation.reactor.core.ReactorAsyncResultExtension` in its build-time classlist by name — the rename breaks Graal native-image builds that depend on that classlist entry.
- Confuses reviewers and merge-conflict resolution when comparing eval output to master.
- Doesn't reduce the diff size or improve any measurable outcome — it's a purely stylistic choice the model made without prompting.

**Rule:** when regenerating an existing module, the top-level Java package of every production class MUST match master exactly. If master uses dotted style (`datadog.trace.instrumentation.reactor.core`), preserve it; if master uses concatenated style (`datadog.trace.instrumentation.rxjava3`), preserve it. Master is the invariant.

### Enumerate all master `*Instrumentation.java` classes on regen

When regenerating an existing module, list every `*Instrumentation.java`, `*Bridge.java`, and helper class currently in master's `src/main/java/.../` directory. Every one of those classes must be either preserved verbatim in the output OR explicitly replaced with an equivalent class. **Dropping a master class without a documented reason is always a Rule #2 violation.**

**Concrete failure pattern (from PR #11940):** master's `reactor-core-3.1` has 7 production classes:

```
ReactorCoreModule.java
ReactorContextBridge.java                 (context-map bridge — see context-tracking.md)
ReactorAsyncResultExtension.java
BlockingPublisherInstrumentation.java
ContextWritingSubscriberInstrumentation.java
CorePublisherInstrumentation.java
OptimizableOperatorInstrumentation.java
```

The eval output kept only 2 (`ReactorCoreModule`, `ReactorAsyncResultExtension`) and added 3 new ones (`FluxInstrumentation`, `MonoInstrumentation`, `TracingCoreSubscriber`). Net effect: 5 master classes silently dropped, including `ReactorContextBridge` — which is what breaks Spring WebFlux, Spring Kafka reactive, and other downstream Reactor-based libraries. No CI check on the target module catches it; the regression only surfaces when sibling-module tests fail.

**How to apply this rule:** before generating, enumerate every `.java` file in the existing module — `find dd-java-agent/instrumentation/<module>/src/main/java -name "*.java"` — and record each filename. After generating, diff that list against the classes in your output. Any master class not present in the output must be explicitly justified in the PR description.

### Preserve declarative-array ordering (`helperClassNames`, `contextStore` keys)

When regenerating an existing `InstrumenterModule`, preserve the exact order of entries in `helperClassNames()` and the exact order of `store.put(...)` calls in `contextStore()`. Reordering entries with no semantic change produces noisy diffs that reviewers correctly reject as "meaningless reshuffling."

**Concrete failure pattern (from PR #11939, @ygree review):** the eval output re-sorted `helperClassNames()` — same set of entries, different order. This adds review burden (reviewer must verify the set is unchanged) with zero benefit.

```java
// ❌ Reordered without reason
return new String[] {
  packageName + ".TracingObserver",
  packageName + ".TracingSubscriber",
  packageName + ".TracingSingleObserver",
  packageName + ".TracingMaybeObserver",
  packageName + ".TracingCompletableObserver",   // was first in master
  packageName + ".RxJavaAsyncResultExtension",
};

// ✅ Preserve master's ordering
return new String[] {
  packageName + ".TracingCompletableObserver",
  packageName + ".TracingObserver",
  packageName + ".TracingSubscriber",
  packageName + ".TracingSingleObserver",
  packageName + ".TracingMaybeObserver",
  packageName + ".RxJavaAsyncResultExtension",
};
```

Only reorder when adding or removing an entry for a semantic reason (a helper is being introduced or retired).

### Hoist repeated `Class.getName()` calls in `contextStore()`

When multiple `store.put(...)` calls in `contextStore()` use the same value-class FQN, hoist `SomeClass.class.getName()` into a single local variable. This is the idiomatic pattern in existing dd-trace-java modules; inlining the same call five times is treated as a regression by reviewers.

```java
// ❌ Inlined at every put site
public Map<String, String> contextStore() {
  final Map<String, String> store = new HashMap<>();
  store.put("io.reactivex.rxjava3.core.Observable",  Context.class.getName());
  store.put("io.reactivex.rxjava3.core.Flowable",    Context.class.getName());
  store.put("io.reactivex.rxjava3.core.Single",      Context.class.getName());
  store.put("io.reactivex.rxjava3.core.Maybe",       Context.class.getName());
  store.put("io.reactivex.rxjava3.core.Completable", Context.class.getName());
  return store;
}

// ✅ Hoisted once
public Map<String, String> contextStore() {
  String contextClass = Context.class.getName();
  final Map<String, String> store = new HashMap<>();
  store.put("io.reactivex.rxjava3.core.Observable",  contextClass);
  store.put("io.reactivex.rxjava3.core.Flowable",    contextClass);
  store.put("io.reactivex.rxjava3.core.Single",      contextClass);
  store.put("io.reactivex.rxjava3.core.Maybe",       contextClass);
  store.put("io.reactivex.rxjava3.core.Completable", contextClass);
  return store;
}
```

Source: @ygree review on PR #11939.

### Do not create a helper class just for CallDepthThreadLocalMap when only one type is instrumented

When only one type is being instrumented, use `CallDepthThreadLocalMap` directly in the Advice class. A separate helper class that just wraps `CallDepthThreadLocalMap.incrementCallDepth` / `decrementCallDepth` adds indirection without value:

```java
// WRONG — pointless wrapper when only one type is instrumented
public class GsonHelper {
    public static boolean shouldSkip() {
        return CallDepthThreadLocalMap.incrementCallDepth(GsonHelper.class) > 0;
    }
    public static void reset() {
        CallDepthThreadLocalMap.reset(GsonHelper.class);
    }
}

// CORRECT — use a target library class as key (instrumentation/module classes are not
// helper-injected and must not appear as literals in inlined advice)
if (CallDepthThreadLocalMap.incrementCallDepth(Gson.class) > 0) return;
// ... in exit:
CallDepthThreadLocalMap.reset(Gson.class);
```

A helper class is appropriate when multiple instrumentation classes share the same depth counter — use the shared sentinel class as the key in that case.

## Advanced: Grouping multiple instrumentations under one module

For complex frameworks with multiple version-specific or feature-specific instrumentations, you can group them under a single `InstrumenterModule` (file ending in `Module.java`). The module class:

- Must extend a `TargetSystem` subclass and have `@AutoService(InstrumenterModule.class)`
- Must implement `typeInstrumentations()` returning a `List<Instrumenter>`
- Must **not** implement an `Instrumenter` interface
- Member instrumentations must **not** carry `@AutoService` and must **not** extend `TargetSystem` subclasses

See `docs/how_instrumentations_work.md` section "Grouping Instrumentations" for details.

## Enrichment helpers must be declared in `helperClassNames()`

If your advice delegates to a helper class (e.g. `SparkJavaRouteEnricher.enrich(...)` from inside `RoutesAdvice`), the helper's fully-qualified class name MUST be listed in `helperClassNames()` on the `InstrumenterModule` — unless the helper is supplied on the boot-class-path (e.g. from `agent-bootstrap`), in which case it is already available without injection:

```java
@Override
public String[] helperClassNames() {
  return new String[] {
    packageName + ".SparkJavaRouteEnricher",
  };
}
```

Without this, the helper class is not loaded into the target application's classloader at instrumentation time, and the advice will `NoClassDefFoundError` at runtime. This is checked by muzzle; a missing helper reference is a common failure mode when refactoring advice.
