package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.core.DDSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AllSamplerTest {

  @Mock DDSpan span;

  @Test
  void testAllSampler() {
    AllSampler sampler = new AllSampler();
    for (int i = 0; i < 500; i++) {
      assertTrue(sampler.sample(span));
    }
  }
}
