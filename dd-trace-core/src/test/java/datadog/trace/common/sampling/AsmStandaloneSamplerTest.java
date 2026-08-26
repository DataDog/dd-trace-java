package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AsmStandaloneSamplerTest extends DDCoreJavaSpecification {

  private final ListWriter writer = new ListWriter();

  @Test
  void testSetSamplingPriority() {
    AtomicLong current = new AtomicLong(System.currentTimeMillis());
    Clock clock = mock(Clock.class);
    when(clock.millis()).thenAnswer(inv -> current.get());
    AsmStandaloneSampler sampler = new AsmStandaloneSampler(clock);
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();

    try {
      doAnswer(inv -> current.updateAndGet(value -> value + 1000))
          .when(clock)
          .millis(); // increment in one second
      DDSpan span1 = (DDSpan) tracer.buildSpan("datadog", "test").start();
      sampler.setSamplingPriority(span1);

      assertEquals(PrioritySampling.SAMPLER_KEEP, span1.getSamplingPriority());

      clearInvocations(clock);

      doAnswer(inv -> current.updateAndGet(value -> value + 1000))
          .when(clock)
          .millis(); // increment in one second
      DDSpan span2 = (DDSpan) tracer.buildSpan("datadog", "test2").start();
      sampler.setSamplingPriority(span2);

      assertEquals(PrioritySampling.SAMPLER_DROP, span2.getSamplingPriority());

      clearInvocations(clock);

      doAnswer(inv -> current.updateAndGet(value -> value + 60000))
          .when(clock)
          .millis(); // increment in one minute
      DDSpan span3 = (DDSpan) tracer.buildSpan("datadog", "test3").start();
      sampler.setSamplingPriority(span3);

      // Mock one minute later
      assertEquals(PrioritySampling.SAMPLER_KEEP, span3.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }
}
