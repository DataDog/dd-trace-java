package datadog.trace.bootstrap.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.sampling.Sampler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ProbeRateLimiterTest {

  private static final int BURST_SIZE = 10_000;

  @BeforeEach
  void setUp() {
    // global sampler is shared: start each test with a fresh budget
    ProbeRateLimiter.setGlobalSnapshotRate(100);
  }

  /**
   * A burst of calls happening within a single sampling window must yield exactly one snapshot when
   * the rate is one snapshot every {@code intervalSeconds}.
   */
  @TableTest({
    "scenario         | intervalSeconds",
    "1 per 10 seconds | 10             ",
    "1 per minute     | 60             ",
    "1 per 5 minutes  | 300            ",
    "1 per hour       | 3600           "
  })
  void subOneRateEmitsOneSnapshotPerBurst(int intervalSeconds) {
    Sampler sampler = ProbeRateLimiter.createSampler(1.0 / intervalSeconds);
    assertEquals(1, countSampled(sampler));
  }

  @TableTest({
    "scenario        | intervalSeconds",
    "1 per 5 minutes | 300            ",
    "1 per hour      | 3600           "
  })
  void subOneRateThroughGlobalLimiterEmitsOneSnapshotPerBurst(int intervalSeconds) {
    Sampler sampler = ProbeRateLimiter.createSampler(1.0 / intervalSeconds);
    int sampled = 0;
    for (int i = 0; i < BURST_SIZE; i++) {
      if (ProbeRateLimiter.tryProbe(sampler, true)) {
        sampled++;
      }
    }
    assertEquals(1, sampled);
  }

  @Test
  void oneOrMorePerSecondRateEmitsSnapshots() {
    assertTrue(countSampled(ProbeRateLimiter.createSampler(1.0)) >= 1);
    assertTrue(
        countSampled(ProbeRateLimiter.createSampler(ProbeRateLimiter.DEFAULT_SNAPSHOT_RATE)) >= 1);
  }

  private static int countSampled(Sampler sampler) {
    int sampled = 0;
    for (int i = 0; i < BURST_SIZE; i++) {
      if (sampler.sample()) {
        sampled++;
      }
    }
    return sampled;
  }
}
