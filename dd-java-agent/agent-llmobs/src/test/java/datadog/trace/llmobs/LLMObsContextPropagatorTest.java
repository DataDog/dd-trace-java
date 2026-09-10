package datadog.trace.llmobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Covers automatic LLM Observability context propagation. Injecting the active span the way
 * auto-instrumentation does must carry the LLMObs context.
 */
class LLMObsContextPropagatorTest {

  private static final String ML_APP_TAG = "_dd.p.llmobs_ml_app";
  private static final String SESSION_ID_TAG = "_dd.p.llmobs_sid";
  private static final String PAGENT_SPAN_ID_TAG = "_dd.p.llmobs_pagent_span_id";
  private static final String PAGENT_NAME_TAG = "_dd.p.llmobs_pagent_name";
  private static final String PARENT_ID_TAG = "_dd.p.llmobs_parent_id";

  private static CoreTracer tracer;

  @BeforeAll
  static void installTracer() {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    Propagators.register(AgentPropagation.LLMOBS_CONCERN, new LLMObsContextPropagator());
  }

  @AfterAll
  static void closeTracer() {
    TracerInstaller.forceInstallGlobalTracer(null);
    tracer.close();
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

  /** What an auto-instrumented client does: inject the active span into an outbound carrier. */
  private static Map<String, String> autoInject(AgentSpan span) {
    Map<String, String> carrier = new HashMap<>();
    Propagators.defaultPropagator().inject(span, carrier, Map::put);
    return carrier;
  }

  @Test
  void stagesLlmObsTagsOnInjectionWithoutAnyManualPropagation() {
    Map<String, String> carrier;
    String agentSpanId;
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agent = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "planner", "my-ml-app", "sess-1");
      agentSpanId = String.valueOf(agent.getSpanId());
      try {
        carrier = autoInject(AgentTracer.activeSpan());
      } finally {
        agent.finish();
      }
    }

    String tags = carrier.get("x-datadog-tags");
    assertNotNull(tags, "expected x-datadog-tags to be injected");
    assertTrue(tags.contains(ML_APP_TAG + "=my-ml-app"), () -> "ml_app missing from " + tags);
    assertTrue(tags.contains(SESSION_ID_TAG + "=sess-1"), () -> "session_id missing from " + tags);
    assertTrue(
        tags.contains(PAGENT_SPAN_ID_TAG + "=" + agentSpanId),
        () -> "pagent_span_id missing from " + tags);
    assertTrue(
        tags.contains(PAGENT_NAME_TAG + "=planner"), () -> "pagent_name missing from " + tags);
    assertTrue(
        tags.contains(PARENT_ID_TAG + "=" + agentSpanId), () -> "parent_id missing from " + tags);
  }

  @Test
  void addsNothingWhenNoLlmObsSpanIsActive() {
    Map<String, String> carrier;
    try (AgentScope apmScope = startRootApmScope()) {
      carrier = autoInject(apmScope.span());
    }

    String tags = carrier.get("x-datadog-tags");
    assertTrue(
        tags == null || !tags.contains("_dd.p.llmobs_"), () -> "unexpected LLMObs tags in " + tags);
  }

  @Test
  void stopsContributingTagsOnceTheLlmObsScopeIsClosed() {
    Map<String, String> carrier;
    try (AgentScope apmScope = startRootApmScope()) {
      newSpan(Tags.LLMOBS_WORKFLOW_SPAN_KIND, "work", "my-ml-app", "sess-1").finish();
      // The LLMObs span has finished; a later outbound call on the same APM trace must not be
      // tagged with a session that is no longer active.
      carrier = autoInject(apmScope.span());
    }

    String tags = carrier.get("x-datadog-tags");
    assertTrue(
        tags == null || !tags.contains(SESSION_ID_TAG),
        () -> "session_id leaked after scope close: " + tags);
    assertTrue(
        tags == null || !tags.contains(PARENT_ID_TAG),
        () -> "parent_id leaked after scope close: " + tags);
  }

  /**
   * The staged tags live on the <em>root</em> span context's propagation tags, which are shared by
   * the whole local trace. Once an injection has written them there, a later injection on the same
   * trace has to overwrite them — otherwise it ships a session and an agent attribution that are no
   * longer active.
   */
  @Test
  void doesNotLeakStagedTagsIntoALaterInjectionOnTheSameTrace() {
    Map<String, String> duringScope;
    Map<String, String> afterScope;
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan agent = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "planner", "my-ml-app", "sess-1");
      try {
        duringScope = autoInject(AgentTracer.activeSpan());
      } finally {
        agent.finish();
      }
      afterScope = autoInject(apmScope.span());
    }

    assertTrue(
        duringScope.get("x-datadog-tags").contains(SESSION_ID_TAG),
        "precondition: the first injection should have staged the LLMObs tags");
    String tags = afterScope.get("x-datadog-tags");
    assertTrue(
        tags == null || !tags.contains("_dd.p.llmobs_"),
        () -> "stale LLMObs tags leaked into a later injection: " + tags);
  }

  /**
   * The full cross-process hop, as an SQS producer/worker pair sees it: the producer injects into
   * message attributes, the worker extracts and activates them, and an LLMObs span started by the
   * worker inherits the session and agent attribution without any application-level plumbing.
   */
  @Test
  void workerInheritsSessionAndAgentAttributionAcrossTheBoundary() {
    Map<String, String> messageAttributes;
    long producerTraceId;
    String producerAgentSpanId;

    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan producer =
          newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "dispatcher", "my-ml-app", "sess-42");
      producerTraceId = producer.getTraceId().toLong();
      producerAgentSpanId = String.valueOf(producer.getSpanId());
      try {
        messageAttributes = autoInject(AgentTracer.activeSpan());
      } finally {
        producer.finish();
      }
    }

    // Worker side: a fresh context, as a message handler would have.
    Context extracted =
        Propagators.defaultPropagator()
            .extract(
                Context.root(), messageAttributes, (carrier, visitor) -> carrier.forEach(visitor));
    AgentSpan consumeSpan = AgentSpan.fromContext(extracted);
    assertNotNull(consumeSpan, "expected trace context to be extracted");

    try (AgentScope consumeScope = AgentTracer.get().activateSpan(consumeSpan)) {
      DDLLMObsSpan workerTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", "my-ml-app", null);
      try {
        assertEquals(producerTraceId, workerTool.getTraceId().toLong(), "trace should be joined");
        // The span publishes its resolved values to the context for its own descendants, so this
        // is what the worker's LLMObs span actually settled on.
        assertEquals("sess-42", LLMObsContext.currentSessionId());
        assertEquals(producerAgentSpanId, LLMObsContext.currentParentAgentSpanId());
        assertEquals("dispatcher", LLMObsContext.currentParentAgentName());
        // The worker's LLMObs span parents onto the producer's, rather than starting a second
        // root — this is the value DDLLMObsSpan reads for its parent_id.
        assertEquals(
            producerAgentSpanId, String.valueOf(consumeSpan.spanContext().getLLMObsParentId()));
      } finally {
        workerTool.finish();
      }
    }
  }

  /**
   * A worker whose own service default differs from the producer's must not re-bucket the trace
   * under its own ml_app. The propagated value outranks the default, so one logical application
   * stays one application across the hop.
   */
  @Test
  void workerInheritsMlAppAcrossTheBoundary() {
    Map<String, String> messageAttributes;
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan producer =
          newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "dispatcher", "checkout", "sess-42");
      try {
        messageAttributes = autoInject(AgentTracer.activeSpan());
      } finally {
        producer.finish();
      }
    }

    assertTrue(
        messageAttributes.get("x-datadog-tags").contains(ML_APP_TAG + "=checkout"),
        () -> "precondition: ml_app should be on the wire: " + messageAttributes);

    Context extracted =
        Propagators.defaultPropagator()
            .extract(
                Context.root(), messageAttributes, (carrier, visitor) -> carrier.forEach(visitor));
    AgentSpan consumeSpan = AgentSpan.fromContext(extracted);
    assertNotNull(consumeSpan, "expected trace context to be extracted");

    try (AgentScope consumeScope = AgentTracer.get().activateSpan(consumeSpan)) {
      // The worker names no ml_app, so its own service default would otherwise apply.
      DDLLMObsSpan workerTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", null, null);
      try {
        assertEquals("checkout", LLMObsContext.currentMlApp());
      } finally {
        workerTool.finish();
      }
    }
  }

  /** An outbound carrier as a producer with an active agent span would have injected it. */
  private static Map<String, String> producerCarrier(String mlApp, String sessionId) {
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan producer = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "dispatcher", mlApp, sessionId);
      try {
        return autoInject(AgentTracer.activeSpan());
      } finally {
        producer.finish();
      }
    }
  }

  /** Activates an extracted carrier the way a message handler or request filter does. */
  private static AgentSpan extractSpan(Map<String, String> carrier) {
    Context extracted =
        Propagators.defaultPropagator()
            .extract(Context.root(), carrier, (c, visitor) -> c.forEach(visitor));
    AgentSpan span = AgentSpan.fromContext(extracted);
    assertNotNull(span, "expected trace context to be extracted");
    return span;
  }

  /**
   * The local server span a consumer's entry-point instrumentation opens for the extracted context.
   * This is the span whose propagation tags {@code CoreTracer} takes over from the extracted one,
   * which is what puts the wire values and anything locally staged in the same place.
   */
  private static AgentScope startLocalChildScope(AgentSpan parent) {
    AgentSpan local =
        AgentTracer.get().buildSpan("apm", "sqs.consume").asChildOf(parent.spanContext()).start();
    return AgentTracer.activateSpan(local);
  }

  /**
   * A pass-through service — a proxy, a router, or any hop that opens no LLMObs span of its own —
   * must keep forwarding the context it received. The staged and the extracted tags share one
   * object, so resetting what this hop staged must restore what arrived rather than clear outright.
   */
  @Test
  void forwardsExtractedContextWhenNoLlmObsSpanIsActive() {
    Map<String, String> inbound = producerCarrier("checkout", "sess-42");
    assertTrue(
        inbound.get("x-datadog-tags").contains(SESSION_ID_TAG + "=sess-42"),
        () -> "precondition: session_id should be on the wire: " + inbound);

    Map<String, String> outbound;
    try (AgentScope consumeScope = startLocalChildScope(extractSpan(inbound))) {
      // No LLMObs span here at all: this hop only relays the call.
      outbound = autoInject(consumeScope.span());
    }

    String tags = outbound.get("x-datadog-tags");
    assertNotNull(tags, "expected x-datadog-tags to be injected");
    for (String tag :
        new String[] {
          ML_APP_TAG + "=checkout", SESSION_ID_TAG + "=sess-42", PAGENT_NAME_TAG + "=dispatcher"
        }) {
      assertTrue(tags.contains(tag), () -> tag + " dropped by the pass-through hop: " + tags);
    }
    assertTrue(tags.contains(PARENT_ID_TAG + "="), () -> "parent_id dropped: " + tags);
    assertTrue(tags.contains(PAGENT_SPAN_ID_TAG + "="), () -> "pagent_span_id dropped: " + tags);
  }

  /**
   * Ordering must not matter: an auto-instrumented outbound call made <em>before</em> the service
   * opens its own LLMObs span resets the staged tags, and that reset must leave the extracted
   * values intact for the span that follows.
   */
  @Test
  void injectionBeforeTheLocalLlmObsSpanDoesNotDestroyExtractedContext() {
    Map<String, String> inbound = producerCarrier("checkout", "sess-42");

    try (AgentScope consumeScope = startLocalChildScope(extractSpan(inbound))) {
      // e.g. a config fetch or a DB call, instrumented and injected before any LLMObs work starts.
      autoInject(consumeScope.span());

      DDLLMObsSpan workerTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", null, null);
      try {
        assertEquals("checkout", LLMObsContext.currentMlApp());
        assertEquals("sess-42", LLMObsContext.currentSessionId());
        assertEquals("dispatcher", LLMObsContext.currentParentAgentName());
      } finally {
        workerTool.finish();
      }
    }
  }

  @Test
  void peerSpanDoesNotInheritAFinishedSpansStagedContext() {
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan dispatcher =
          newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "dispatcher", "checkout", "sess-42");
      try {
        // Stages the five tags on the root span context's propagation tags, which the whole local
        // trace shares. Nothing clears them when the span finishes.
        autoInject(AgentTracer.activeSpan());
      } finally {
        dispatcher.finish();
      }

      // A second LLMObs span on the same trace, with no LLMObs parent of its own. Whatever is still
      // staged belongs to a span that has finished, so it isn't upstream context and must not be
      // read as such.
      DDLLMObsSpan peer = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "unrelated", "billing", null);
      try {
        assertEquals("billing", LLMObsContext.currentMlApp());
        assertNull(LLMObsContext.currentSessionId());
        assertNull(LLMObsContext.currentParentAgentSpanId());
        assertNull(LLMObsContext.currentParentAgentName());
      } finally {
        peer.finish();
      }
    }
  }

  @Test
  void workerWithoutUpstreamLlmObsContextInheritsNothing() {
    Map<String, String> messageAttributes;
    try (AgentScope apmScope = startRootApmScope()) {
      messageAttributes = autoInject(apmScope.span());
    }

    Context extracted =
        Propagators.defaultPropagator()
            .extract(
                Context.root(), messageAttributes, (carrier, visitor) -> carrier.forEach(visitor));
    AgentSpan consumeSpan = AgentSpan.fromContext(extracted);
    assertNotNull(consumeSpan, "expected trace context to be extracted");

    try (AgentScope consumeScope = AgentTracer.get().activateSpan(consumeSpan)) {
      DDLLMObsSpan workerTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", "my-ml-app", null);
      try {
        assertNull(LLMObsContext.currentSessionId());
        assertNull(LLMObsContext.currentParentAgentSpanId());
        assertNull(consumeSpan.spanContext().getLLMObsParentId());
      } finally {
        workerTool.finish();
      }
    }
  }
}
