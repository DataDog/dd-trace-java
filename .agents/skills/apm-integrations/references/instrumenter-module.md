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

Before creating `dd-java-agent/instrumentation/$framework/$framework-$version/`, list the parent directory `dd-java-agent/instrumentation/$framework/` to see what's already there:

```
ls dd-java-agent/instrumentation/$framework/
# e.g. commons-httpclient-2.0/  (already exists)
```

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

**New module you expect will imminently sibling** — add the alias upfront and document why in the commit message. If no sibling appears, drop the alias in a follow-up.

**Existing module** (modifying, refactoring, or splitting): read the existing module's `super(...)` and copy it verbatim. Integration names are public config API — renaming one silently breaks customer `DD_TRACE_*_ENABLED` settings.

Before choosing, run `ls dd-java-agent/instrumentation/$framework/` — the directory contents are the ground truth for whether siblings exist.

Do NOT add version aliases to the decorator's `instrumentationNames()` — that method is for analytics keys only.

_Rationale traceable to reviewer comments: single-name-for-single-module (Valentin Zakharov on PR #11709, comment `3532152120`); version alias when siblings exist (Stuart McCulloch on PR #11760, comment `3552616459`)._

**When regenerating an existing module, preserve every override the master version has.** Not just `super(...)` — also `defaultEnabled()`, `helperClassNames()`, `contextStore()`, `orderPriority()`, `muzzleDirective()`, and any other overridden method. Read the current version of the file (on master) before generating; carry each override forward verbatim unless there's a documented reason to change it. Silent loss of `defaultEnabled() = false` (or similar opt-in flags) ships an integration with a different default than users expected.

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

If your advice delegates to a helper class (e.g. `SparkJavaRouteEnricher.enrich(...)` from inside `RoutesAdvice`), the helper's fully-qualified class name MUST be listed in `helperClassNames()` on the `InstrumenterModule`:

```java
@Override
public String[] helperClassNames() {
  return new String[] {
    packageName + ".SparkJavaRouteEnricher",
  };
}
```

Without this, the helper class is not loaded into the target application's classloader at instrumentation time, and the advice will `NoClassDefFoundError` at runtime. This is checked by muzzle; a missing helper reference is a common failure mode when refactoring advice.
