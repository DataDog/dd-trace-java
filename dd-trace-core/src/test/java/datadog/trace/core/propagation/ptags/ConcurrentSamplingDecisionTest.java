package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.DATADOG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.core.propagation.PropagationTags;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Concurrent decisions on one trace's tags must behave as if they ran one at a time, in some order.
 * Each trial races two decisions whose outcome is the same in either order, so a lost update shows
 * up as a different result.
 */
class ConcurrentSamplingDecisionTest {
  private static final int TRIALS = 2_000;

  private final PropagationTags.Factory factory = PropagationTags.factory();
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void shutdown() {
    executor.shutdownNow();
  }

  /** A manual keep overrides a sampler decision made before it and blocks one made after it. */
  @Test
  void forceKeepWinsOverRacingSamplerDecision() throws Exception {
    for (int trial = 0; trial < TRIALS; ++trial) {
      PropagationTags ptags = factory.empty();

      race(
          () -> ptags.forceKeep(MANUAL),
          () ->
              ptags.tryUpdateProbabilitySamplingDecision(
                  SAMPLER_KEEP, AGENT_RATE, 0.5, false, 1L, false));

      assertEquals(USER_KEEP, ptags.getSamplingPriority(), "trial " + trial);
      assertEquals("-4", String.valueOf(ptags.samplingState().getDecisionMaker()));
    }
  }

  /**
   * Locking the decision maker wins over a racing decision: one made before it is overwritten, and
   * one made after it sees the decision maker locked. The racing decision's own decision maker
   * ({@code -1}) differs from the locked one, so a decision that slips in after the lock shows.
   */
  @Test
  void lockedDecisionMakerWinsOverRacingDecision() throws Exception {
    PropagationTags source = factory.fromHeaderValue(DATADOG, "_dd.p.dm=-3");
    for (int trial = 0; trial < TRIALS; ++trial) {
      PropagationTags ptags = factory.empty();

      race(
          () -> ptags.updateAndLockDecisionMaker(source),
          () -> ptags.updateTraceSamplingPriority(USER_KEEP, AGENT_RATE));

      assertEquals(
          "-3", String.valueOf(ptags.samplingState().getDecisionMaker()), "trial " + trial);
    }
  }

  private void race(Runnable first, Runnable second) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    Future<?> a = executor.submit(() -> runAfter(start, first));
    Future<?> b = executor.submit(() -> runAfter(start, second));
    start.countDown();
    a.get();
    b.get();
  }

  private static void runAfter(CountDownLatch start, Runnable action) {
    try {
      start.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    action.run();
  }
}
