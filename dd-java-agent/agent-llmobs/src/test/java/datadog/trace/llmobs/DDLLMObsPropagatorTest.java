package datadog.trace.llmobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.propagation.Propagators;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import datadog.trace.llmobs.domain.DDLLMObsSpan;
import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Round-trips {@link DDLLMObsPropagator} through a plain {@code Map<String, String>} carrier — the
 * shape a customer's own SQS message-attribute map would take.
 */
class DDLLMObsPropagatorTest {

  private static CoreTracer tracer;
  private final DDLLMObsPropagator propagator = new DDLLMObsPropagator();

  @BeforeAll
  static void installTracer() {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
  }

  @AfterAll
  static void closeTracer() {
    TracerInstaller.forceInstallGlobalTracer(null);
    tracer.close();
  }

  private static DDLLMObsSpan newAgentSpan(String name, String mlApp, String sessionId) {
    return newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, name, mlApp, sessionId);
  }

  private static DDLLMObsSpan newToolSpan(String name, String mlApp, String sessionId) {
    return newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, name, mlApp, sessionId);
  }

  private static DDLLMObsSpan newSpan(String kind, String name, String mlApp, String sessionId) {
    WellKnownTags tags =
        new WellKnownTags("runtime-id", "hostname", "test", "service", "version", "java");
    return new DDLLMObsSpan(kind, name, mlApp, sessionId, "service", tags);
  }

  private static AgentScope startRootApmScope() {
    AgentSpan root = AgentTracer.get().buildSpan("apm", "sqs.produce").start();
    return AgentTracer.activateSpan(root);
  }

  @Test
  void injectRequiresNonNullSpanAndHeaders() {
    assertThrows(
        NullPointerException.class,
        () -> propagator.injectDistributedHeaders(null, new HashMap<>()));
  }

  @Test
  void activateRequiresNonNullHeaders() {
    assertThrows(NullPointerException.class, () -> propagator.activateDistributedHeaders(null));
  }

  @Test
  void activateWithoutTraceContextIsNoOp() throws Exception {
    // Compare against whatever LLMObsContext happened to be ambient going in, rather than
    // asserting a global null baseline — this test runs alongside many others in the same JVM
    // and must not assume it is the only thing that has ever touched ambient context.
    Object ambientBefore = LLMObsContext.current();
    try (Closeable scope = propagator.activateDistributedHeaders(new HashMap<>())) {
      assertEquals(ambientBefore, LLMObsContext.current());
    }
  }

  @Test
  void injectThenActivateJoinsSameTraceAndPropagatesLlmObsContext() throws Exception {
    Map<String, String> headers = new HashMap<>();

    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan producerAgent = newAgentSpan("producer-agent", "my-ml-app", "session-123");
      long producerTraceId = producerAgent.getTraceId().toLong();
      try {
        propagator.injectDistributedHeaders(producerAgent, headers);
      } finally {
        producerAgent.finish();
      }

      // Simulate the consumer side: a different message handled with no ambient context.
      try (Closeable consumerScope = propagator.activateDistributedHeaders(headers)) {
        DDLLMObsSpan consumerTool = newToolSpan("consumer-tool", "my-ml-app", null);
        try {
          assertEquals(producerTraceId, consumerTool.getTraceId().toLong());
          assertEquals("session-123", consumerTool.getSessionId());
        } finally {
          consumerTool.finish();
        }
      }
    }
  }

  /**
   * The automatic path: no LLMObs propagation API is called at all. Injecting the active span the
   * way any auto-instrumented HTTP/gRPC client does must still carry the LLMObs context, matching
   * dd-trace-py's {@code http.span_inject} hook.
   */
  @Test
  void autoInstrumentedInjectCarriesLlmObsContextWithoutManualPropagation() {
    Propagators.register(AgentPropagation.LLMOBS_CONCERN, new LLMObsContextPropagator());
    Map<String, String> headers = new HashMap<>();

    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agent = newAgentSpan("producer-agent", "my-ml-app", "session-123");
      try {
        Propagators.defaultPropagator().inject(agent.getAgentSpan(), headers, Map::put);
      } finally {
        agent.finish();
      }
    }

    String xDatadogTags = headers.get("x-datadog-tags");
    assertNotNull(xDatadogTags, "expected x-datadog-tags to be injected");
    assertTrue(
        xDatadogTags.contains("_dd.p.llmobs_ml_app=my-ml-app"),
        () -> "ml_app missing from " + xDatadogTags);
    assertTrue(
        xDatadogTags.contains("_dd.p.llmobs_sid=session-123"),
        () -> "session_id missing from " + xDatadogTags);
  }

  /** An outbound call made with no LLMObs span active must not pick up LLMObs tags. */
  @Test
  void autoInstrumentedInjectAddsNothingWithoutAnActiveLlmObsSpan() {
    Propagators.register(AgentPropagation.LLMOBS_CONCERN, new LLMObsContextPropagator());
    Map<String, String> headers = new HashMap<>();

    try (AgentScope apmScope = startRootApmScope()) {
      Propagators.defaultPropagator().inject(apmScope.span(), headers, Map::put);
    }

    String xDatadogTags = headers.get("x-datadog-tags");
    assertTrue(
        xDatadogTags == null || !xDatadogTags.contains("_dd.p.llmobs_"),
        () -> "unexpected LLMObs tags in " + xDatadogTags);
  }

  @Test
  void injectAlwaysIncludesMlAppEvenWithoutSessionIdOrAttribution() {
    Map<String, String> headers = new HashMap<>();
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan standaloneTool = newToolSpan("standalone-tool", "my-ml-app", null);
      try {
        propagator.injectDistributedHeaders(standaloneTool, headers);
        String xDatadogTags = headers.get("x-datadog-tags");
        // ml_app is always present since it's required on every LLMObs span.
        assertTrue(xDatadogTags != null && xDatadogTags.contains("_dd.p.llmobs_ml_app=my-ml-app"));
      } finally {
        standaloneTool.finish();
      }
    }
  }
}
