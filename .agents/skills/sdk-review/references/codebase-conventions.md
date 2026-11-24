# Codebase conventions

Repo-specific idioms: configuration, logging, shared helpers, test patterns, and build tooling. [AGENTS.md](../../../../AGENTS.md) also applies.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line and no `Not when:` applies, or when it has a clear problem in this lens's area that no rule covers (mark it `no rule`). Cite the rule ID and name the helper or pattern to use.

## 1. Configuration

### CONV-1.1: Add new config keys through a `*Config` constants class, `ConfigDefaults`, `Config` and `supported-configurations.json`
- Flag: a new key read as a string literal or via `System.getProperty("dd.…")`/`System.getenv("DD_…")`; a new constant in `dd-trace-api/.../api/config/*Config.java` that includes the `dd.`/`DD_` prefix; or a new key without a `final` field, getter and `toString()` entry in `Config` plus an entry in `metadata/supported-configurations.json` (`version`, `type`, `default`, `aliases`), whose `type` matches the equivalent existing key (e.g. `boolean`, not `string`, for a flag). See [add_new_configurations.md](../../../../docs/add_new_configurations.md#adding-a-configuration).
- ❌ `public static final String START_DELAY = "dd.dogstatsd.start_delay";`
- ✅ `public static final String DOGSTATSD_START_DELAY = "dogstatsd.start-delay";`

### CONV-1.2: Read configuration through `Config` getters, not `ConfigProvider`
- Flag: `ConfigProvider.getInstance().getString(...)`/`getBoolean(...)` outside `Config.java` to re-query a setting that `Config` already holds, or a mutable `Config` field changed at runtime instead of going through `DynamicConfig`. See [add_new_configurations.md](../../../../docs/add_new_configurations.md#where-configurations-live).
- ❌ `ConfigProvider.getInstance().getBoolean(TRACE_128_BIT_TRACEID_GENERATION_ENABLED, true)`
- ✅ `Config.get().isTrace128bitTraceIdGenerationEnabled()`

### CONV-1.3: Register every `InstrumenterModule` name in `supported-configurations.json`
- Flag: a new or added name in an instrumentation's `super("name", ...)` without a matching `DD_TRACE_<NAME>_ENABLED` entry (with `DD_TRACE_INTEGRATION_<NAME>_ENABLED` and `DD_INTEGRATION_<NAME>_ENABLED` aliases), one entry per name. See [add_new_instrumentation.md](../../../../docs/add_new_instrumentation.md#create-the-instrumentation-class).

## 2. Logging and errors

### CONV-2.1: Narrow quieted exception logging to the expected exception types and log `e.getMessage()`
- Flag: a `catch (Exception e)` whose log call drops the stack trace (`e.toString()`/`e.getMessage()`) for every exception, when only some (e.g. `ClosedSelectorException`) are expected; keep passing `e` for unexpected ones.
- ❌ `catch (Exception e) { ratelimitedLogger.warn("Error parsing response: {}", e.toString()); }`
- ✅ `catch (ClosedSelectorException e) { log.debug("Selector closed: {}", e.getMessage()); } catch (Exception e) { ratelimitedLogger.warn("Error parsing response", e); }`

## 3. Shared helpers and idioms

### CONV-3.1: Static-import call-style helpers and repeated constants; never use wildcard imports
- Flag: repeated class-qualified calls such as `Assertions.assertEquals`, `Mockito.mock`, `Collections.emptyMap()`, `Collections.singletonList(...)`, or repeated `Tags.`/`DDTags.`/config-key constants, and any `import x.*`/`import static x.*`. See [CONTRIBUTING.md](../../../../CONTRIBUTING.md#static-imports).
- Not when: the static import would be ambiguous (e.g. `Tags.SPAN_KIND_SERVER` vs `DDSpanContext.SPAN_KIND_SERVER`).
- ❌ `List<List<PojoSpan>> traces = Collections.singletonList(Collections.singletonList(span));`
- ✅ `List<List<PojoSpan>> traces = singletonList(singletonList(span));`

### CONV-3.2: Annotate members widened for tests with `@VisibleForTesting`
- Flag: a new package-private/`public` accessor or field used only by tests (e.g. `isSerializerThreadAlive()`, `queueSize()`) without `datadog.trace.api.internal.VisibleForTesting`.
- ✅ `@VisibleForTesting int queueSize() { return queue.size(); }`

### CONV-3.3: Make static-helper classes `final`
- Flag: a new class made only of static methods (e.g. a `*Helper`/`*Utils` class) declared without `final` and not designed for extension.
- ❌ `public class JpmsHelper {`
- ✅ `public final class JpmsHelper {`

### CONV-3.4: Document methods with Javadoc, not `//` comments
- Flag: a `//` or plain `/* ... */` block above a method declaration that describes the method. See [AGENTS.md](../../../../AGENTS.md#code-conventions).
- ❌ `// Stores a value outside the caller-controlled budget...`
- ✅ `/** Stores a value outside the caller-controlled budget... */`

### CONV-3.5: Use the Context API directly outside advice; use `Java8BytecodeBridge` only in advice
- Flag: `Java8BytecodeBridge.currentContext()`/`currentSpan()` in helpers, decorators, handlers or tests (non-advice code), or `fromContext(current())` where `AgentSpan.current()` reads the active span.
- Not when: the code is in an `@Advice` method, where static interface methods like `AgentSpan.current()`/`Context.current()` are not allowed and the bridge is required.
- ❌ `observed.set(fromContext(current()));`
- ✅ `observed.set(AgentSpan.current());`

### CONV-3.6: Use braces for every `if` body
- Flag: a braceless one-line `if`/`else` body; google-java-format doesn't add braces.
- ❌ `if (span == null) return;`
- ✅ `if (span == null) { return; }`

## 4. Instrumentation conventions

### CONV-4.1: Lay out new instrumentations as `dd-java-agent/instrumentation/{framework}/{framework}-{minVersion}/` with the module plugin
- Flag: a new instrumentation module outside that layout, missing its `include(":dd-java-agent:instrumentation:$framework:$framework-$minVersion")` in `settings.gradle.kts` (alpha order), or a `build.gradle` that applies `gradle/java.gradle` instead of `id 'dd-trace-java.module.instrumentation'`. See [add_new_instrumentation.md](../../../../docs/add_new_instrumentation.md#configuring-gradle).

### CONV-4.2: Give instrumentation modules a versioned alias and the family's shared alias
- Flag: `super("name")` with no versioned alias (e.g. `gax-1.4`), or a new module in a family (e.g. OpenTelemetry) that omits the alias its siblings share (e.g. `opentelemetry-1`).
- ❌ `super("gax");`
- ✅ `super("gax", "gax-1.4");`

### CONV-4.3: Keep instrumentation-only classes in the instrumentation module as helper classes
- Flag: a class used only by one instrumentation's advice moved into `dd-trace-core`/`internal-api` or reached via `TracerAPI` because it "can't be imported", or a manual `helperClassNames()` override for helpers that bytecode discovery already finds (a non-empty manual list replaces discovery). See [add_new_instrumentation.md](../../../../docs/add_new_instrumentation.md#add-helper-class-names).

### CONV-4.4: Apply `dd-trace-java.instrumentation.netty-concurrent-tests` for Netty-based tests and group related dependencies
- Flag: a hand-added `testImplementation project(':dd-java-agent:instrumentation:netty:netty-concurrent-4.0')` instead of the convention plugin, or related instrumentation dependencies (e.g. netty modules) scattered across a `dependencies {}` block. See [how_to_test.md](../../../../docs/how_to_test.md#netty-instrumentation-tests).

## 5. Tests

### CONV-5.1: Write new unit tests as JUnit 5 Java tests in a named package under `src/test/java`
- Flag: a new `.groovy` unit test or Spock `Specification`, Java sources added under a Groovy source set, or a new Java test class in the default package. See [AGENTS.md](../../../../AGENTS.md#code-conventions).
- Not when: the test is an instrumentation or smoke test, which may still be Groovy/Spock.

### CONV-5.2: Pick the parameterized source per the JUnit guide: `@ValueSource` for one value, `@TableTest` for multi-column data
- Flag: `@TableTest` with a single boolean/string column, `@CsvSource`, or a `@MethodSource` returning `arguments(...)` of literals only. See [how_to_test_with_junit.md](../../../../docs/how_to_test_with_junit.md#when-to-use-which).
- ❌ `@TableTest({"finallyFail", "false", "true"})`
- ✅ `@ParameterizedTest @ValueSource(booleans = {false, true})`

### CONV-5.3: Use a `@TypeConverter` for symbolic constants instead of switching to `@MethodSource`
- Flag: a `@MethodSource` provider whose rows differ only by constants like `DD64bTraceId.MAX`, `DDSpanId.MAX` or `Long.MAX_VALUE`; register a converter with `@TypeConverterSources`, reusing `TableTestTypeConverters` where possible. See [how_to_test_with_junit.md](../../../../docs/how_to_test_with_junit.md#unparsable-constants-with-typeconverter).
- ❌ `arguments(DD64bTraceId.MAX, 2L, 3L)` in a `Stream<Arguments>` provider
- ✅ `"max | DD64bTraceId.MAX | 2 | 3"` in `@TableTest` with a trace-ID `@TypeConverter`

### CONV-5.4: Use config-key constants in `@WithConfig`
- Flag: `@WithConfig(key = "...")` with a string literal when a constant exists in a `*Config` class. See [how_to_test_with_junit.md](../../../../docs/how_to_test_with_junit.md#config-with-constant-references).
- ❌ `@WithConfig(key = "trace.128.bit.traceid.generation.enabled", value = "false")`
- ✅ `@WithConfig(key = TRACE_128_BIT_TRACEID_GENERATION_ENABLED, value = "false")`

### CONV-5.5: Suffix tests that need a fresh JVM with `ForkedTest`
- Flag: a test that mutates agent state, static singletons or system properties in a way other tests would see, in a class not named `*ForkedTest`. See [how_to_test.md](../../../../docs/how_to_test.md#forked-tests).

### CONV-5.6: Use `@Flaky` and `JavaVirtualMachine` helpers for unreliable or JVM-specific tests
- Flag: `@Disabled` or a commented-out test for a flaky case instead of `@Flaky` (`datadog.trace.test.util.Flaky`), or `@DisabledIfSystemProperty(named = "java.vendor", ...)` instead of a `datadog.environment.JavaVirtualMachine` condition. See [how_to_test.md](../../../../docs/how_to_test.md#flaky-tests).
- ❌ `@DisabledIfSystemProperty(named = "java.vendor", matches = "(?i).*IBM.*")`
- ✅ `@DisabledIf("datadog.environment.JavaVirtualMachine#isIbm")` (or `@Flaky(conditionMethod = "datadog.environment.JavaVirtualMachine#isIbm")`)

### CONV-5.7: Declare Testcontainers images with `testContainerImage`, not hard-coded tags
- Flag: `new GenericContainer("redis:7-alpine")` or another container constructor with an image string; declare it in Gradle with the `dd-trace-java.testcontainers` plugin and read the system property without a fallback. See [how_to_test.md](../../../../docs/how_to_test.md#tests-that-use-containers).
- ❌ `new CassandraContainer("cassandra:4")`
- ✅ `new RedisContainer(DockerImageName.parse(System.getProperty("test.redis.image")).asCompatibleSubstituteFor("redis"))`

### CONV-5.8: Use the specific JUnit assertion for the check
- Flag: `assertTrue(x instanceof T)`/`assertTrue(T.class.isInstance(x))`, `assertEquals(true, ...)`/`assertEquals(null, ...)`, or a manual `try`/`fail()`/`catch` instead of `assertThrows`.
- ❌ `assertTrue(result instanceof NoopSpan);`
- ✅ `assertInstanceOf(NoopSpan.class, result);`

## 6. Build and repository hygiene

### CONV-6.1: Regenerate `gradle.lockfile` files with every dependency change
- Flag: a change to `dependencies {}` in a build script or to `gradle/libs.versions.toml` without the matching `gradle.lockfile` updates in the same PR. See [how_to_work_with_gradle.md](../../../../docs/how_to_work_with_gradle.md#updating-lock-files).

### CONV-6.2: Keep instrumentation-specific library versions out of `gradle/libs.versions.toml`
- Flag: new entries in the main version catalog for a library used only by instrumentations (e.g. `okhttp3-minimum`); keep the version in the module's `build.gradle` or a dedicated catalog.

### CONV-6.3: Keep AI-generated planning documents out of the repository
- Flag: new files under `docs/superpowers/plans/` or similar agent plan/scratch markdown (e.g. `*-implementation-plan.md`) not referenced by any doc.

### CONV-6.4: Write agent instructions positively and match sibling skill frontmatter
- Flag: new `AGENTS.md`/`SKILL.md` guidance phrased as negations ("never use `//`…") where a positive form works ("use Javadoc for…"), or a new `.agents/skills/*/SKILL.md` that omits `user-invocable`, `context` or `allowed-tools` when its siblings set them for comparable behavior (file edits, GitHub mutations, Gradle runs).

## Don't flag
- `@ParametersAreNonnullByDefault` on each class instead of in `package-info.java` when sibling classes in the package already annotate per class.
- Unqualified static-imported helper calls; preferring class-qualified calls contradicts [CONTRIBUTING.md](../../../../CONTRIBUTING.md#static-imports).
