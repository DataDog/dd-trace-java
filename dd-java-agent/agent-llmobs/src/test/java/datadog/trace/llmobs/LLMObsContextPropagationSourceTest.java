package datadog.trace.llmobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.llmobs.LLMObsInternal;
import datadog.trace.api.llmobs.LLMObsPropagationValues;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import datadog.trace.llmobs.domain.DDLLMObsSpan;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Covers automatic LLM Observability context propagation. Injecting the active span the way
 * auto-instrumentation does must carry the LLMObs context.
 */
class LLMObsContextPropagationSourceTest {

  private static final String TRACE_ID_TAG = "_dd.p.llmobs_trace_id";
  private static final String ML_APP_TAG = "_dd.p.llmobs_ml_app";
  private static final String SESSION_ID_TAG = "_dd.p.llmobs_sid";
  private static final String PAGENT_SPAN_ID_TAG = "_dd.p.llmobs_pagent_span_id";
  private static final String PAGENT_NAME_TAG = "_dd.p.llmobs_pagent_name";
  private static final String PARENT_ID_TAG = "_dd.p.llmobs_parent_id";
  private static final String SAMPLE_RATE_TAG = "_dd.p.llmobs_sr";
  private static final String SAMPLING_DECISION_TAG = "_dd.p.llmobs_sd";

  private static CoreTracer tracer;

  @BeforeAll
  static void installTracer() {
    tracer = CoreTracer.builder().build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    LLMObsInternal.setPropagationSource(new LLMObsContextPropagationSource());
  }

  @AfterAll
  static void closeTracer() {
    LLMObsInternal.setPropagationSource(null);
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

  /**
   * The LLMObs parent span id the context carries, or {@code null} when it carries no LLMObs
   * context at all. This is the value DDLLMObsSpan reads for its parent_id.
   */
  private static String propagatedParentId(AgentSpan span) {
    LLMObsPropagationValues values = span.spanContext().getExtractedLLMObsValues();
    return values == null ? null : values.parentId;
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

  @Test
  void writesLlmObsTagsOnInjectionWithoutAnyManualPropagation() {
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

  /**
   * An injection resolves the LLMObs context that is active at that moment and writes nothing back
   * to the span context, which the whole local trace shares. So a later injection on the same
   * trace, with no LLMObs span active, ships no session and no agent attribution.
   */
  @Test
  void doesNotLeakOneInjectionsContextIntoALaterInjectionOnTheSameTrace() {
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
        "precondition: the first injection should have written the LLMObs tags");
    String tags = afterScope.get("x-datadog-tags");
    assertTrue(
        tags == null || !tags.contains("_dd.p.llmobs_"),
        () -> "stale LLMObs tags leaked into a later injection: " + tags);
  }

  /**
   * The full cross-process hop, as an SQS producer/worker pair sees it: the producer injects into
   * message attributes, the worker extracts and activates them, and an LLMObs span started by the
   * worker inherits ml_app, session and agent attribution without any application-level plumbing.
   * The worker names no ml_app of its own, so this also covers precedence — the propagated value
   * outranks the worker's service default, keeping one logical application intact across the hop.
   */
  @Test
  void workerInheritsLlmObsContextAcrossTheBoundary() {
    Map<String, String> messageAttributes;
    long producerTraceId;
    String producerAgentSpanId;

    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan producer =
          newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "dispatcher", "checkout", "sess-42");
      producerTraceId = producer.getTraceId().toLong();
      producerAgentSpanId = String.valueOf(producer.getSpanId());
      try {
        messageAttributes = autoInject(AgentTracer.activeSpan());
      } finally {
        producer.finish();
      }
    }

    assertTrue(
        messageAttributes.get("x-datadog-tags").contains(ML_APP_TAG + "=checkout"),
        () -> "precondition: ml_app should be on the wire: " + messageAttributes);

    // Worker side: a fresh context, as a message handler would have.
    AgentSpan consumeSpan = extractSpan(messageAttributes);
    try (AgentScope consumeScope = AgentTracer.get().activateSpan(consumeSpan)) {
      // The worker names no ml_app, so its own service default would otherwise apply.
      DDLLMObsSpan workerTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", null, null);
      try {
        assertEquals(producerTraceId, workerTool.getTraceId().toLong(), "trace should be joined");
        // The span publishes its resolved values to the context for its own descendants, so this
        // is what the worker's LLMObs span actually settled on.
        assertEquals("checkout", LLMObsContext.currentMlApp());
        assertEquals("sess-42", LLMObsContext.currentSessionId());
        assertEquals(producerAgentSpanId, LLMObsContext.currentParentAgentSpanId());
        assertEquals("dispatcher", LLMObsContext.currentParentAgentName());
        // The worker's LLMObs span parents onto the producer's, rather than starting a second
        // root — this is the value DDLLMObsSpan reads for its parent_id.
        assertEquals(producerAgentSpanId, propagatedParentId(consumeSpan));
      } finally {
        workerTool.finish();
      }
    }
  }

  /**
   * An LLMObs trace is not the APM trace: it starts at the first LLMObs span and can outlive or
   * skip whole APM traces, so it carries an id of its own. With no upstream id to adopt, this
   * service is that start, and seeds the id from the APM trace it is already on — which is what
   * keeps a Java-only trace reporting exactly the id it reported before this tag existed.
   *
   * <p>On the wire the id is the unsigned 128-bit decimal integer that dd-trace-py parses with
   * {@code int()}, even though it is hex everywhere else.
   */
  @Test
  void writesTheLlmObsTraceIdAsTheDecimalTheWireCarries() {
    Map<String, String> carrier;
    String llmObsTraceId;
    String apmTraceId;
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan producer = newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "dispatcher", "checkout", null);
      llmObsTraceId = producer.getLLMObsTraceId();
      apmTraceId = producer.getTraceId().toHexString();
      try {
        carrier = autoInject(AgentTracer.activeSpan());
      } finally {
        producer.finish();
      }
    }

    assertEquals(apmTraceId, llmObsTraceId, "an LLMObs trace root should seed from its APM trace");
    String wire = new BigInteger(llmObsTraceId, 16).toString();
    assertTrue(
        carrier.get("x-datadog-tags").contains(TRACE_ID_TAG + "=" + wire),
        () -> "llmobs trace id missing from " + carrier.get("x-datadog-tags"));
  }

  /**
   * The cross-language case this tag exists for. An upstream tracer runs an LLMObs trace whose id
   * is unrelated to the APM trace id — it began at an LLMObs span several hops back. Reading the
   * APM trace id instead, as this tracer used to, would report the hop as a separate LLMObs trace
   * and split the distributed trace in the UI.
   */
  @Test
  void workerAdoptsAnLlmObsTraceIdThatDiffersFromTheApmTrace() {
    String upstreamTraceId = "6d0b1e9c00000000a1b2c3d4e5f60718";
    Map<String, String> inbound = producerCarrier("checkout", null);
    inbound.put(
        "x-datadog-tags",
        inbound
            .get("x-datadog-tags")
            .replaceAll(
                "_dd\\.p\\.llmobs_trace_id=[0-9]+",
                TRACE_ID_TAG + "=" + new BigInteger(upstreamTraceId, 16)));

    try (AgentScope consumeScope = startLocalChildScope(extractSpan(inbound))) {
      DDLLMObsSpan worker = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", null, null);
      try {
        assertEquals(upstreamTraceId, worker.getLLMObsTraceId());
        assertEquals(upstreamTraceId, LLMObsContext.currentTraceId());
        assertNotEquals(
            upstreamTraceId,
            worker.getTraceId().toHexString(),
            "precondition: the local APM trace id should differ from the adopted LLMObs one");

        // Descendants keep the adopted id rather than falling back to the local APM trace.
        DDLLMObsSpan child = newSpan(Tags.LLMOBS_LLM_SPAN_KIND, "generate", null, null);
        try {
          assertEquals(upstreamTraceId, child.getLLMObsTraceId());
        } finally {
          child.finish();
        }
      } finally {
        worker.finish();
      }
    }
  }

  /**
   * A pass-through service — a proxy, a router, or any hop that opens no LLMObs span of its own —
   * must keep forwarding the context it received. With no local LLMObs context to resolve, the
   * codec writes the extracted values instead.
   *
   * <p>Ordering must not matter either: an outbound call injected <em>before</em> the service opens
   * an LLMObs span of its own must leave the extracted values intact for the span that follows,
   * which is what the second half of this test checks.
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

      // Still inside the same hop, after that injection has already reset the staged tags: an
      // LLMObs span opened now must still see what arrived on the wire.
      DDLLMObsSpan workerTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", null, null);
      try {
        assertEquals("checkout", LLMObsContext.currentMlApp());
        assertEquals("sess-42", LLMObsContext.currentSessionId());
        assertEquals("dispatcher", LLMObsContext.currentParentAgentName());
      } finally {
        workerTool.finish();
      }
    }

    String tags = outbound.get("x-datadog-tags");
    assertNotNull(tags, "expected x-datadog-tags to be injected");
    for (String tag :
        new String[] {
          ML_APP_TAG + "=checkout", SESSION_ID_TAG + "=sess-42", PAGENT_NAME_TAG + "=dispatcher"
        }) {
      assertTrue(tags.contains(tag), () -> tag + " dropped by the pass-through hop: " + tags);
    }
    assertTrue(tags.contains(TRACE_ID_TAG + "="), () -> "llmobs trace id dropped: " + tags);
    assertTrue(tags.contains(PARENT_ID_TAG + "="), () -> "parent_id dropped: " + tags);
    assertTrue(tags.contains(PAGENT_SPAN_ID_TAG + "="), () -> "pagent_span_id dropped: " + tags);
  }

  /** A span that samples locally also publishes the verdict it reached onto the wire. */
  @Test
  void writesTheSamplingVerdictOnInjection() {
    Map<String, String> carrier = producerCarrier("checkout", null);

    String wire = carrier.get("x-datadog-tags");
    assertTrue(wire.contains(SAMPLE_RATE_TAG + "=1"), () -> "sample rate missing from " + wire);
    assertTrue(
        wire.contains(SAMPLING_DECISION_TAG + "=1"),
        () -> "sampling decision missing from " + wire);
  }

  /**
   * The sampling verdict has to survive a process hop, otherwise a consumer configured at a
   * different rate than its producer re-rolls and the distributed LLMObs trace is retained only in
   * part. The upstream here dropped at rate 0.1 while this service keeps everything: without
   * propagation the consumer would report a decision of "1" against its own rate.
   */
  @Test
  void consumerHonoursThePropagatedSamplingVerdict() {
    Map<String, String> inbound = producerCarrier("checkout", null);
    // Stand in for an upstream configured at a different rate than this service's default of 1.0.
    inbound.put(
        "x-datadog-tags",
        inbound
            .get("x-datadog-tags")
            .replace(SAMPLE_RATE_TAG + "=1", SAMPLE_RATE_TAG + "=0.1")
            .replace(SAMPLING_DECISION_TAG + "=1", SAMPLING_DECISION_TAG + "=0"));

    try (AgentScope consumeScope = startLocalChildScope(extractSpan(inbound))) {
      DDLLMObsSpan consumer = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", null, null);
      try {
        assertEquals("0", LLMObsContext.currentSamplingDecision());
        assertEquals("0.1", LLMObsContext.currentSampleRate());
      } finally {
        consumer.finish();
      }
    }
  }

  @Test
  void peerSpanDoesNotInheritAFinishedSpansInjectedContext() {
    try (AgentScope apmScope = startRootApmScope()) {
      DDLLMObsSpan dispatcher =
          newSpan(Tags.LLMOBS_AGENT_SPAN_KIND, "dispatcher", "checkout", "sess-42");
      try {
        autoInject(AgentTracer.activeSpan());
      } finally {
        dispatcher.finish();
      }

      // A second LLMObs span on the same trace, with no LLMObs parent of its own. The finished
      // span's context is not upstream context and must not be read as such.
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

    AgentSpan consumeSpan = extractSpan(messageAttributes);
    try (AgentScope consumeScope = AgentTracer.get().activateSpan(consumeSpan)) {
      DDLLMObsSpan workerTool = newSpan(Tags.LLMOBS_TOOL_SPAN_KIND, "handler", "my-ml-app", null);
      try {
        assertNull(LLMObsContext.currentSessionId());
        assertNull(LLMObsContext.currentParentAgentSpanId());
        assertNull(propagatedParentId(consumeSpan));
      } finally {
        workerTool.finish();
      }
    }
  }
}
