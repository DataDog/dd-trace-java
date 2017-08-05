package com.datadoghq.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.datadoghq.trace.sampling.RandomSampler;
import com.datadoghq.trace.sampling.Sampler;
import org.junit.Test;

public class RandomSamplerTest {

  @Test
  public void testRandomSampler() {

    final DDSpan mockSpan = mock(DDSpan.class);

    final double sampleRate = 0.35;
    final int iterations = 1000;
    final Sampler sampler = new RandomSampler(sampleRate);

    int kept = 0;

    for (int i = 0; i < iterations; i++) {
      if (sampler.sample(mockSpan)) {
        kept++;
      }
    }
    //FIXME test has to be more predictable
    //assertThat(((double) kept / iterations)).isBetween(sampleRate - 0.02, sampleRate + 0.02);

  }

  @Test
  public void testRateBoundaries() {

    RandomSampler sampler = new RandomSampler(1000);
    assertThat(sampler.getSampleRate()).isEqualTo(1);

    sampler = new RandomSampler(-1000);
    assertThat(sampler.getSampleRate()).isEqualTo(1);

    sampler = new RandomSampler(0.337);
    assertThat(sampler.getSampleRate()).isEqualTo(0.337);
  }
}
