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
 * Regression guard for the "local children share the parent's {@link PropagationTags}" optimization
 * in {@code CoreTracer.buildSpanContext}: a non-root (local child) span must still carry the
 * inbound distributed {@code _dd.p.*} tags when it injects.
 *
 * <p>Inbound {@code _dd.p.*} live on the root's {@code PropagationTags} (inherited from the {@link
 * ExtractedContext}), and reads route to the root via {@code DDSpanContext.getPropagationTags()} —
 * so a local child injects the same tags whether it holds its own instance or shares the root's.
 * That invariant is what lets the child skip allocating its own {@code empty()} and share the
 * root's instead. If {@link #localChildCarriesInboundDdpTags()} regresses, non-root injection is
 * dropping inbound {@code _dd.p.*} on every hop.
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
    return ((DDSpanContext) span.spanContext())
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
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root.spanContext()).start();
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
