package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AsmStandaloneSamplerTest extends DDCoreSpecification {

  @Mock Clock clock;

  @Test
  void testSetSamplingPriority() {
    AtomicLong current = new AtomicLong(System.currentTimeMillis());

    // The constructor calls clock.millis() once during initialization
    Mockito.when(clock.millis()).thenAnswer(invocation -> current.get());

    AsmStandaloneSampler sampler = new AsmStandaloneSampler(clock);
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();
    try {
      // First span: advance time by 1 second so that setSamplingPriority's clock.millis()
      // returns a value 1 second after initialization
      Mockito.when(clock.millis())
          .thenAnswer(invocation -> current.updateAndGet(value -> value + 1000));

      DDSpan span1 = (DDSpan) tracer.buildSpan("test").start();
      sampler.setSamplingPriority(span1);

      assertEquals((int) PrioritySampling.SAMPLER_KEEP, span1.getSamplingPriority().intValue());

      // Second span: advance time by another 1 second (not enough to reset the 1-minute window)
      Mockito.when(clock.millis())
          .thenAnswer(invocation -> current.updateAndGet(value -> value + 1000));

      DDSpan span2 = (DDSpan) tracer.buildSpan("test2").start();
      sampler.setSamplingPriority(span2);

      assertEquals((int) PrioritySampling.SAMPLER_DROP, span2.getSamplingPriority().intValue());

      // Third span: advance time by 1 minute (resets the window)
      Mockito.when(clock.millis())
          .thenAnswer(invocation -> current.updateAndGet(value -> value + 60000));

      DDSpan span3 = (DDSpan) tracer.buildSpan("test3").start();
      sampler.setSamplingPriority(span3);

      assertEquals((int) PrioritySampling.SAMPLER_KEEP, span3.getSamplingPriority().intValue());
    } finally {
      tracer.close();
    }
  }
}
