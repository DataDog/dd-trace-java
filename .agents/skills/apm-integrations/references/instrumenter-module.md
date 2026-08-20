# InstrumenterModule Guidance

> Referenced from `SKILL.md` Step 5. Everything needed to write the `InstrumenterModule` class correctly.

## Conventions to enforce

- Add `@AutoService(InstrumenterModule.class)` annotation — required for auto-discovery
- Extend the correct `InstrumenterModule.*` subclass (never the bare abstract class)
- Implement the **narrowest** `Instrumenter` interface possible:
  - Prefer `ForSingleType` > `ForKnownTypes` > `ForTypeHierarchy`
  - **EXCEPTION — API specification / interface-only libraries**: when the target library is a specification JAR containing only interfaces (no concrete classes), `ForSingleType` does not work because there are no concrete types to instrument directly. You MUST use `ForTypeHierarchy` with `implementsInterface(named("the.interface.Fqn"))`. This is how vendor implementations of the specification (ActiveMQ, IBM MQ, EclipseLink, Hibernate, etc.) get instrumented through the common interface contract.
  - **EXCEPTION applies even when you are handed a CONCRETE implementation, not the spec jar.** The trigger is "does this type implement a shared JDK/spec SPI that other vendors also implement?" — NOT "is the coordinate an interface-only jar?" If you are given a single concrete driver (e.g. `org.postgresql:postgresql`, whose `org.postgresql.jdbc.PgStatement` implements `java.sql.Statement`), you MUST still hook the SPI interface via `ForTypeHierarchy` + `implementsInterface(named("java.sql.Statement"))`, NOT the concrete class via `ForSingleType(named("org.postgresql.jdbc.PgStatement"))`. Hooking the concrete class (a) covers only that one vendor while the SPI hook covers all conforming drivers with one module, and (b) collides at runtime with the existing SPI module that already instruments the same interface — both fire on the same object and mutually suppress spans via the shared `CallDepthThreadLocalMap.incrementCallDepth(<SpiType>.class)` guard. Before instrumenting any concrete class, check whether it implements a type already listed below; if so, the existing SPI module already covers it — do not generate a parallel per-vendor module. **Scope this prohibition to behaviorally-redundant advice.** Implementing a shared SPI does NOT mean every relevant behavior is already advised: vendor-only lifecycle or compatibility hooks that are NOT declared on the SPI legitimately require a concrete/vendor module (e.g. `DBMCompatibleConnectionInstrumentation` deliberately matches concrete JDBC connection classes to add DBM prepare behavior absent from the generic SPI advice; DB2 has vendor-specific JDBC modules; Tomcat instruments concrete `Request.recycle()` alongside the servlet SPI). Reject a concrete hook only when its target method is already advised through the SPI (`ForTypeHierarchy` + `implementsInterface(...)`); allow implementation-only behavior the SPI cannot express.
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
  - **Database-client design rules** (applied when picking the hook target in Step 3 / writing the module in Step 5): keep the connection/session `db.instance`/keyspace as the DEFAULT, and OVERRIDE it per-operation only when the operation supplies a more specific value (e.g. a fully-qualified `other_ks.table` query, or keyspace read from the response `ColumnDefinitions`) — never drop the default, or operations with no result metadata (a Cassandra write) lose the tag; prefer a tracing wrapper of long-lived client objects (wrap the client's factory return value) over per-`execute` advice; gate DBM metadata collection behind the DBM-enabled flag; and (R-DB-3) populate connection metadata eagerly at `Driver.connect` into a `Connection`-keyed context store, while retaining a lazy `parseDBInfo`-style fallback for connections created through paths the connect hook doesn't cover (DataSource, proxy). These are the human-readable statements of the rules; when this skill is driven by the apm-instrumentation-toolkit they are additionally enforced as force-read prompt blocks in the toolkit (see apm-instrumentation-toolkit#580), which is what makes the generator apply them at analyze/code-gen. The toolkit file is not part of this repo.
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

**The integration name you are given may NOT match the existing family directory.** Before creating a module, grep the whole tree for your intended `super(...)` name: `grep -rn 'super("<name>"' dd-java-agent/instrumentation/`. Treat matches as **evidence, not a unique placement key** — then confirm with the library coordinates, target packages, and muzzle ranges:
- **Matches form one version family of the same library** (e.g. `datastax-cassandra-3.0/`, `-3.8/`, `-4.0/` all `super("cassandra")`) → your module MUST join that family's directory as `<existing-family-dir>/<family>-<version>/`; do NOT create a new top-level module under a different slug.
- **A shared *configuration* name spans genuinely unrelated families** (e.g. `super("jax-rs", ...)` legitimately appears under independent `rs`, `jersey`, `resteasy` families; `ci-visibility` spans nine) → the name does NOT dictate one directory. Place the module by the **target library's** coordinates/packages, and reuse the enablement name as intended.
- **The name search points to multiple plausible families and the target coordinates don't disambiguate** → STOP and surface the ambiguity rather than guessing.

(Note: equal `super(...)` names do not by themselves cause a registration outage — `InstrumenterIndex.loadModules()` indexes by module class, not by `name()`. The real failure is a *duplicate module for the same library/version*, below.)

**Concrete failure (Cassandra regen, R-DB-1):** the eval was given the integration slug `cassandra`, but dd-trace-java's family directory is `datastax-cassandra/` with siblings `datastax-cassandra-3.0/`, `-3.8/`, `-4.0/`, all declaring `super("cassandra")`. The agent created a new top-level `instrumentation/cassandra/` module that also declared `super("cassandra")`, producing two `@AutoService(InstrumenterModule.class)` registrations for the same name. Result: a **silent tracing outage** — ByteBuddy advice failed to apply, zero spans, all tests timed out, and there was no build error to catch it. This is especially dangerous under the blind protocol: if the same-version master module was deleted, "modify it in place" has no target — but the surviving siblings still hold the name, so grepping for the name (not looking for a same-version directory) is what tells you where the module belongs. When the name is taken and the correct family directory differs from the slug you were handed, place the module in the family directory and match the siblings' `super(...)` exactly; if there is genuinely no correct home without colliding, STOP and surface it rather than shipping a parallel registration.

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

**Editing an existing module:** read it first, then change only what the task requires. Copy `super(...)` verbatim — integration names are public config API, and silently changing the number of arguments or a string value breaks customer `DD_TRACE_*_ENABLED` settings. The same "read before touching, preserve unless you have a reason to change it" rule covers everything else on the class: overridden methods (`defaultEnabled()`, `helperClassNames()`, `contextStore()`, `orderPriority()`), the Java package, the set and order of production classes, and array/map entry ordering. Don't rename, reorder, drop, or restructure any of it as a side effect of regenerating the file — every one of those looks harmless in isolation but reads as an unexplained regression to a reviewer, and some (like a dropped `*ContextBridge` helper — see `context-tracking.md`) silently break sibling modules that reference the class by FQN.

Do NOT add version aliases to the decorator's `instrumentationNames()` — that method is for analytics keys only.

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

### Database clients: populate connection metadata EAGERLY at connect time, not lazily per query

For database-client integrations (`DatabaseClientDecorator` / `DBTypeProcessingDatabaseClientDecorator`), capture connection metadata (host, port, db name, user) at **connection-establishment** time and cache it in a `ContextStore` keyed on the connection object — not lazily on the first query. The canonical pattern is a dedicated instrumentation on the connect/factory method:

- **JDBC** — `dd-java-agent/instrumentation/jdbc/DriverInstrumentation.java` hooks `Driver.connect(url, props)` and populates `InstrumentationContext.get(Connection.class, DBInfo.class)` at open time. Statement advice then reads the already-cached `DBInfo`.
- **Reactive drivers with an async connect** — the equivalent connect point is the connection FACTORY, not the connection object. For R2DBC, `io.r2dbc.spi.ConnectionFactoryOptions` is the only place host/port/database/user are exposed as structured data; `io.r2dbc.spi.ConnectionMetadata` (on the live `Connection`) exposes ONLY product name/version. **But `ConnectionFactory.create()` is a zero-argument SPI method returning a `Publisher<? extends Connection>` — the options are NOT available at `create()`.** Capture them earlier, where the factory is built: hook `ConnectionFactories.get(ConnectionFactoryOptions)` (or the provider-construction path) and store the options in a `ContextStore<ConnectionFactory, ConnectionFactoryOptions>`; then, in advice on `create()`, read the stored options for that factory and thread them onto the asynchronously-emitted `Connection` (a second context store keyed on the returned `Connection`). Hooking only `Connection.createStatement()` + `ConnectionMetadata` CANNOT populate `db.name`/`peer.hostname`/`db.user`/port. (OpenTelemetry's R2DBC instrumentation does exactly this options→factory→connection threading; it is a good reference.)

Why eager-at-connect beats lazy-per-query: lazy extraction (e.g. `statement.getConnection().getMetaData().getURL()` on first execute) works for plain JDBC but (a) pays the extraction cost on every connection's first query instead of amortizing at pool-open, and (b) silently yields nothing when the metadata is not reachable from the object the query advice happens to hold — which is exactly what happens for reactive drivers whose statement/connection objects don't carry the factory options.

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
