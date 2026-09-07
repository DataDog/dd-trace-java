package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.trace.core.DDSpan;
import org.junit.jupiter.api.Test;

class AllSamplerTest {

  private final DDSpan span = mock(DDSpan.class);
  private final AllSampler sampler = new AllSampler();

  @Test
  void testAllSampler() {
    for (int i = 0; i < 500; i++) {
      assertTrue(sampler.sample(span));
    }
  }
}
