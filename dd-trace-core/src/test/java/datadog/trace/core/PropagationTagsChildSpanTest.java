package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization test for the open question in {@code ptags-allocation-findings.md}: when a
 * distributed trace arrives carrying {@code _dd.p.*} propagation tags, does a <b>non-root (local
 * child)</b> span still carry those inbound tags when it injects?
 *
 * <p>Inbound {@code _dd.p.*} live on the root's {@link PropagationTags} (inherited from the {@link
 * ExtractedContext}, {@code CoreTracer} ~line 2031), but a local child currently receives a fresh
 * {@code propagationTagsFactory.empty()} instance (~line 2020). If {@link
 * #localChildCarriesInboundDdpTags()} <b>fails</b>, non-root injection is dropping inbound {@code
 * _dd.p.*} — a latent correctness bug, not merely the known per-span allocation waste. If it
 * <b>passes</b>, there is reconciliation and the per-span empties are pure waste (sharing the
 * parent's instance is then a safe allocation win).
 *
 * <p>Either way this test is the gate + safety net for the planned "share the parent's
 * PropagationTags for local children" fix.
 */
class PropagationTagsChildSpanTest extends DDCoreJavaSpecification {

  private static final String INBOUND_HEADER = "_dd.p.dm=934086a686-4,_dd.p.anytag=value";
  private static final String INBOUND_TAG = "_dd.p.anytag=value";

  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().build();
  }

  private static ExtractedContext extractedWithDdpTags() {
    return new ExtractedContext(
        DDTraceId.ONE,
        2,
        PrioritySampling.SAMPLER_KEEP,
        null,
        0,
        Collections.<String, String>emptyMap(),
        Collections.<String, Object>emptyMap(),
        null,
        PropagationTags.factory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, INBOUND_HEADER),
        null,
        DATADOG);
  }

  /** What the Datadog codec would inject for {@code x-datadog-tags} from this span. */
  private static String injectedDdpHeader(AgentSpan span) {
    return ((DDSpanContext) span.context())
        .getPropagationTags()
        .headerValue(PropagationTags.HeaderType.DATADOG);
  }

  /** Baseline: the root span (built directly from the extracted context) carries inbound tags. */
  @Test
  void rootSpanCarriesInboundDdpTags() {
    AgentSpan root = tracer.buildSpan("test", "root").asChildOf(extractedWithDdpTags()).start();
    try {
      String header = injectedDdpHeader(root);
      assertTrue(
          header != null && header.contains(INBOUND_TAG),
          "root injected _dd.p.* header should contain inbound tag; was: " + header);
    } finally {
      root.finish();
    }
  }

  /**
   * THE open question: a local child of the root. Inbound {@code _dd.p.*} must survive injection
   * from the child, or downstream services lose the distributed tags on every non-root hop.
   */
  @Test
  void localChildCarriesInboundDdpTags() {
    AgentSpan root = tracer.buildSpan("test", "root").asChildOf(extractedWithDdpTags()).start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root.context()).start();
    try {
      String header = injectedDdpHeader(child);
      assertTrue(
          header != null && header.contains(INBOUND_TAG),
          "local child injected _dd.p.* header should contain inbound tag; was: " + header);
    } finally {
      child.finish();
      root.finish();
    }
  }
}
