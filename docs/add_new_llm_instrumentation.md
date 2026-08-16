# Add a New LLM Framework Instrumentation

This guide explains how to add instrumentation for a new LLM framework using the shared
LLM SPI (`LlmObsHandle` / `LlmCallHandle`). The SPI emits three correlated signals for
every instrumented operation:

- **APM span** — visible in Trace Explorer; carries `component=<framework>` and the
  appropriate `span.kind`
- **LLMObs span** — visible in LLM Observability; carries input/output messages and token
  metrics
- **JFR duration event** — visible in Continuous Profiler; carries the LLMObs (or APM)
  span's `traceId`/`spanId` for cross-product correlation

The [LangChain4j 1.0 instrumentation](../dd-java-agent/instrumentation/langchain4j/langchain4j-1.0)
is the reference implementation for this pattern.

> [!IMPORTANT]
> **This SPI is the proposed unified approach for all new LLM instrumentations.**
> New integrations — whether they target a framework like LangChain4j or a direct API
> client like an OpenAI SDK — should use `LlmCallHandle` and `LlmObsHandle` as the
> single lifecycle contract.
>
> The existing OpenAI Java SDK instrumentation pre-dates this SPI and is kept at its
> current state (standalone `AgentSpan`-based decorator pattern) out of caution: its
> async/streaming response-wrapper pattern requires additional design work before the
> `LlmCallHandle` lifecycle can be applied safely. It will be migrated in a follow-up.
> Do not model new work on it.

## Prerequisites

- Familiarity with [How Instrumentations Work](./how_instrumentations_work.md)
- Java 11 or later (JFR event classes are not available on Java 8)
- The new module must set `testJvmConstraints { minJavaVersion = JavaVersion.VERSION_17 }`
  in its `build.gradle`

## Module layout

```
dd-java-agent/instrumentation/<framework>/<framework>-<minVersion>/
  build.gradle
  src/
    main/java/datadog/trace/instrumentation/<framework>/
      <Framework>ProfilingModule.java   ← InstrumenterModule
      <Framework>LlmObsIntegration.java ← factory / span-start helpers
      ChatModelInstrumentation.java     ← one file per intercepted type
      ...
    testFixtures/java/datadog/trace/instrumentation/llm/tck/
      (LlmObsHandleTck.java is inherited from langchain4j — see TCK section)
    test/java/datadog/trace/instrumentation/<framework>/
      LlmCallHandleTckTest.java         ← extends LlmObsHandleTck
```

## Step 1 — Register the module

Add the new module to `settings.gradle.kts` in alpha order with the other
instrumentations (see [Configuring Gradle](./add_new_instrumentation.md#configuring-gradle)):

```kotlin
":dd-java-agent:instrumentation:<framework>:<framework>-<minVersion>",
```

## Step 2 — Create `build.gradle`

```gradle
plugins {
  id 'java-test-fixtures'
}

apply from: "$rootDir/gradle/java.gradle"

def minVer = '<minimum supported version>'

testJvmConstraints {
  minJavaVersion = JavaVersion.VERSION_17
}

// JFR event classes require Java 11+
tasks.named("compileJava", JavaCompile) {
  configureCompiler(it, 17, JavaVersion.VERSION_1_8, "LLM instrumentation requires Java 11+ for JFR")
}
tasks.named("compileTestJava", JavaCompile) {
  configureCompiler(it, 17, JavaVersion.VERSION_1_8, "LLM instrumentation requires Java 11+ for JFR")
}
tasks.named("compileTestFixturesJava", JavaCompile) {
  configureCompiler(it, 17, JavaVersion.VERSION_1_8, "LLM TCK requires Java 11+ for JFR")
}

muzzle {
  pass {
    group = "<group>"
    module = "<core-module>"
    versions = "[$minVer,)"
  }
}

addTestSuiteForDir('latestDepTest', 'test')

dependencies {
  // TCK — mandatory for every LLM instrumentation
  testFixturesApi project(':dd-java-agent:agent-bootstrap')
  testFixturesCompileOnly project(':internal-api')
  testFixturesCompileOnly libs.bundles.junit5
  testFixturesCompileOnly libs.bundles.mockito

  compileOnly group: '<group>', name: '<core-module>', version: minVer
  testImplementation group: '<group>', name: '<full-module>', version: minVer
}
```

## Step 3 — Create the `InstrumenterModule`

The module class activates when **any** of tracing, profiling, or LLMObs is enabled and
registers the integration factory as a helper class:

```java
@AutoService(InstrumenterModule.class)
public class MyFrameworkModule extends InstrumenterModule {

  public MyFrameworkModule() {
    super("my-framework");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TRACING)
        || enabledSystems.contains(PROFILING)
        || enabledSystems.contains(LLMOBS);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MyFrameworkLlmObsIntegration"};
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ChatModelInstrumentation(),
        /* add further type instrumentations here */);
  }
}
```

> [!IMPORTANT]
> `helperClassNames()` must list **only** classes that are loaded by the instrumentation
> classloader (i.e. your own integration classes). Bootstrap classes
> (`AgentTracer`, `Tags`, `UTF8BytesString`, `LlmCallHandle`, etc.) are on the bootstrap
> classpath and must **not** be listed here.

## Step 4 — Create the integration factory

The factory class creates and returns `LlmObsHandle` instances. It must:

1. Start a JFR event and call `begin()` on it
2. Check whether each backend is active before starting spans
3. Activate the APM span **inside a try/catch** so an exception from the LLMObs
   factory cannot leave an orphaned scope on the thread-local stack
4. Fall back to APM span IDs for JFR correlation when LLMObs is disabled

Use `LangChain4jLlmObsIntegration` as the reference implementation:

```java
public final class MyFrameworkLlmObsIntegration {

  public static final MyFrameworkLlmObsIntegration INSTANCE =
      new MyFrameworkLlmObsIntegration();

  private static final String INSTRUMENTATION_NAME = "my-framework";
  private static final CharSequence OPERATION_LLM =
      UTF8BytesString.create("my-framework.chat_model.request");

  private MyFrameworkLlmObsIntegration() {}

  public LlmObsHandle startLlm(String modelId) {
    MyChatModelEvent jfrEvent = new MyChatModelEvent(modelId);
    boolean jfrActive = jfrEvent.isEnabled();
    boolean obsActive = LLMObs.isEnabled();
    AgentScope agentScope = startApmSpan(OPERATION_LLM, modelId, Tags.SPAN_KIND_CLIENT);
    if (!jfrActive && !obsActive && agentScope == null) return LlmObsHandle.NOOP;
    try {
      LLMObsSpan obsSpan =
          obsActive ? LLMObs.startLLMSpan(modelId, modelId, null, null, null) : null;
      setJfrSpanContext(jfrEvent, jfrActive, obsSpan, agentScope);
      return new LlmCallHandle(jfrActive ? jfrEvent : null, obsSpan, agentScope);
    } catch (Throwable t) {
      abortApmSpan(agentScope);
      return LlmObsHandle.NOOP;
    }
  }

  private static AgentScope startApmSpan(
      CharSequence operationName, String resourceName, String spanKind) {
    if (!AgentTracer.isRegistered() || !Config.get().isTraceEnabled()) return null;
    AgentSpan span = AgentTracer.startSpan(INSTRUMENTATION_NAME, operationName);
    span.setTag(Tags.COMPONENT, INSTRUMENTATION_NAME);
    span.setTag(Tags.SPAN_KIND, spanKind);
    span.setResourceName(resourceName != null ? resourceName : "unknown");
    return AgentTracer.activateSpan(span);
  }

  private static void abortApmSpan(AgentScope agentScope) {
    if (agentScope == null) return;
    agentScope.span().finish();
    agentScope.close();
  }
}
```

### Span kind conventions

| Operation type | `span.kind` |
|---|---|
| LLM chat / completion call (external network) | `Tags.SPAN_KIND_CLIENT` |
| AI service / orchestration (internal) | `Tags.SPAN_KIND_INTERNAL` |
| Tool execution (local code) | `Tags.SPAN_KIND_INTERNAL` |

## Step 5 — Create JFR event classes

Create one event class per logical operation tier. Place them in
`agent-bootstrap/src/main/java11/datadog/trace/bootstrap/instrumentation/jfr/llm/` and
apply the `@LLMOperation` meta-annotation:

```java
@Name("datadog.MyChatModel")
@Label("My Chat Model")
@Description("LLM chat model invocation via My Framework")
@Category({"Datadog", "LLM"})
@StackTrace(false)
@LLMOperation
public class MyChatModelEvent extends Event {

  @Label("Model Id")
  private final String modelId;

  @Label("Trace ID")
  private String traceId;

  @Label("Span ID")
  private String spanId;

  public MyChatModelEvent(String modelId) {
    this.modelId = modelId;
    begin();
  }

  public void setSpanContext(String traceId, String spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }
}
```

Register the events in the JFR configuration file
`dd-java-agent/agent-profiling/profiling-controller-jfr/src/main/resources/jfr/dd.jfp`:

```
datadog.MyChatModel#enabled=true
```

> [!NOTE]
> Do **not** add a `#threshold` unless you intentionally want to suppress short-lived
> events. LLM operations should always be recorded regardless of duration.

## Step 6 — Write advice classes

Each advice class intercepts one target method. Use `@Advice.Local("handle")` to pass
the `LlmObsHandle` between enter and exit, and match `suppress = Throwable.class` on
both sides so the agent never crashes the instrumented application:

```java
public static final class ChatAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(
      @Advice.Argument(0) MyChatRequest request,
      @Advice.Local("handle") LlmObsHandle handle) {
    if (request == null) return;
    handle = MyFrameworkLlmObsIntegration.INSTANCE.startLlm(request.modelId());
    // populate inputs from the request
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Local("handle") LlmObsHandle handle,
      @Advice.Return MyChatResponse response,
      @Advice.Thrown Throwable err) {
    if (handle == null) return;
    if (response != null) {
      handle.withOutput(/* extract output from response */);
      // optionally: handle.withTokenMetrics(in, out);
    }
    if (err != null) handle.withError(err);
    handle.finish();
  }
}
```

> [!WARNING]
> Always guard `@Advice.Argument(0)` with a `null` check before calling any method on it.
> ByteBuddy injects `null` when the argument is unavailable, and a `NullPointerException`
> is silently swallowed by `suppress = Throwable.class` — leaving no span and no error
> signal.

### Handle lifecycle summary

```
enter:  handle = integration.startLlm(modelId)
        handle.withInput(messages)
exit:   handle.withOutput(messages)
        handle.withTokenMetrics(inputTokens, outputTokens)  // if available
        handle.withError(thrown)                            // if thrown != null
        handle.finish()
```

`finish()` is idempotent and thread-safe; calling it more than once is harmless.

## Step 7 — Pass the TCK

Every LLM instrumentation **must** pass `LlmObsHandleTck`. The TCK is published as a
test fixture of the `langchain4j-1.0` module and verifies the `LlmCallHandle` lifecycle
contract: finish idempotency, scope-before-span ordering, exception safety, error
propagation, I/O forwarding, and async scope lifecycle.

### Add the TCK dependency

```gradle
// build.gradle
dependencies {
  testImplementation(testFixtures(project(':dd-java-agent:instrumentation:langchain4j:langchain4j-1.0')))
  // ...
}
```

### Extend the TCK

Create a test class that extends `LlmObsHandleTck` and supplies the JFR event class
specific to the new integration:

```java
class MyChatModelHandleTckTest extends LlmObsHandleTck {

  @Override
  protected Event makeJfrEvent() {
    // Return a JFR event with begin() already called, as advice does at method entry.
    return new MyChatModelEvent("test-model");
  }
}
```

The TCK runs 15 test cases automatically. No further test code is required to satisfy the
contract.

> [!NOTE]
> The TCK tests the **handle lifecycle** only (mocked APM scope and LLMObs span). It does
> not test that advice fires correctly under the agent, nor that the integration factory
> wires spans end-to-end with real infrastructure. Those are covered by separate
> instrumented tests (see [How to Test](./how_to_test.md)).

## Checklist

Before opening a PR for a new LLM instrumentation:

- [ ] `LlmCallHandleTckTest` (or equivalent) passes all 15 TCK cases
- [ ] `@Advice.Argument(0)` guarded with `null` check in every enter advice
- [ ] APM span kind is `SPAN_KIND_CLIENT` for external LLM calls, `SPAN_KIND_INTERNAL` for
      orchestration and local tool execution
- [ ] `startApmSpan` is gated on `Config.get().isTraceEnabled()` (not just
      `AgentTracer.isRegistered()`) to avoid emitting APM spans in PROFILING-only or
      LLMOBS-only deployments
- [ ] JFR events registered in `dd.jfp` without a `#threshold`
- [ ] `helperClassNames()` lists only the integration factory class, not bootstrap classes
- [ ] New JFR event class annotated with `@LLMOperation` and placed in the
      `bootstrap/instrumentation/jfr/llm` package
- [ ] Module registered in `settings.gradle.kts`
- [ ] PR carries `inst:<framework>`, `type:feature`, and `tag: ai generated` labels
