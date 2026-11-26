package datadog.trace.core.datastreams;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OriginSamplerTest {
  private static final long START = SECONDS.toNanos(5_000);

  @Test
  void atMostTheCapIsSampledPerSecondAcrossAllEdges() {
    OriginSampler sampler = new OriginSampler(1000, START);
    long[] edges = {11, 22, 33, 44, 55};
    for (long edge : edges) {
      assertTrue(sampler.sample(edge, START), "first message on an edge");
    }

    int sampled = 0;
    for (int i = 0; i < 5000; i++) {
      long now = START + MILLISECONDS.toNanos(i / 10);
      if (sampler.sample(edges[i % edges.length], now)) {
        sampled++;
      }
    }

    assertEquals(1000, sampled);
  }

  @Test
  void theBudgetRefillsEachSecond() {
    OriginSampler sampler = new OriginSampler(2, START);
    sampler.sample(7, START);
    assertTrue(sampler.sample(7, START));
    assertTrue(sampler.sample(7, START));
    assertFalse(sampler.sample(7, START));

    long nextSecond = START + SECONDS.toNanos(1);
    assertTrue(sampler.sample(7, nextSecond));
    assertTrue(sampler.sample(7, nextSecond));
    assertFalse(sampler.sample(7, nextSecond));
  }

  @Test
  void aNewEdgeIsSampledWhenTheBudgetIsSpent() {
    OriginSampler sampler = new OriginSampler(1, START);
    sampler.sample(1, START);
    sampler.sample(1, START);
    assertFalse(sampler.sample(1, START));

    assertTrue(sampler.sample(2, START));
    assertFalse(sampler.sample(2, START));
  }
}
