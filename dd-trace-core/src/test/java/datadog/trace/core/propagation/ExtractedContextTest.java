package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.SamplingMechanism.EXTERNAL_OVERRIDE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.DDTraceId;
import org.junit.jupiter.api.Test;

class ExtractedContextTest {
  @Test
  void replacesSamplingPriorityWithoutChangingTraceIdentity() {
    PropagationTags propagationTags = PropagationTags.factory().empty();
    propagationTags.updateTraceSamplingPriority(SAMPLER_DROP, EXTERNAL_OVERRIDE);
    ExtractedContext original =
        new ExtractedContext(
            DDTraceId.from(42), 43, SAMPLER_DROP, null, propagationTags, TRACECONTEXT);

    ExtractedContext updated = original.withSamplingPriority(UNSET);

    assertSame(original, updated);
    assertEquals(original.getTraceId(), updated.getTraceId());
    assertEquals(original.getSpanId(), updated.getSpanId());
    assertEquals(UNSET, updated.getSamplingPriority());
    assertEquals(UNSET, updated.getPropagationTags().getSamplingPriority());
  }

  @Test
  void replacesSamplingPriorityWithoutPropagationTags() {
    ExtractedContext context =
        new ExtractedContext(DDTraceId.from(42), 43, SAMPLER_DROP, null, null, TRACECONTEXT);

    context.withSamplingPriority(UNSET);

    assertEquals(UNSET, context.getSamplingPriority());
  }
}
