package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.PropagationTags.SamplingState;
import org.junit.jupiter.api.Test;

/**
 * The tracestate is held apart from the sampling state, which only changes when the tracestate's
 * {@code ot} member does.
 */
class TracestateUpdateTest {
  private static final PropagationTags.Factory FACTORY = PropagationTags.factory();
  private static final String WITH_OTEL_MEMBER = "dd=s:1,ot=rv:0123456789abcd;th:8";

  @Test
  void tracestateWithoutOtelMemberKeepsSamplingState() {
    PropagationTags ptags = FACTORY.empty();
    SamplingState before = ptags.samplingState();

    ptags.updateW3CTracestate("vendor=state");

    assertSame(before, ptags.samplingState());
    assertEquals("vendor=state", ptags.getW3CTracestate());
  }

  @Test
  void otelMemberOfNewTracestateIsTaken() {
    PropagationTags ptags = FACTORY.empty();

    ptags.updateW3CTracestate(WITH_OTEL_MEMBER);

    CharSequence otel = ptags.samplingState().getOtelTraceState();
    assertNotNull(otel);
    assertEquals("rv:0123456789abcd;th:8", otel.toString());
  }

  @Test
  void otelMemberIsDroppedWithItsTracestate() {
    PropagationTags ptags = FACTORY.fromHeaderValue(W3C, WITH_OTEL_MEMBER);
    assertNotNull(ptags.samplingState().getOtelTraceState());

    ptags.updateW3CTracestate("vendor=state");

    assertNull(ptags.samplingState().getOtelTraceState());
    assertEquals("vendor=state", ptags.getW3CTracestate());
  }
}
