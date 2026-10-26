package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.core.propagation.PropagationTags;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KnuthSamplingRateCacheTest {
  private static final double[] RATES = {0.5, 0.25, 0.1, 0.75};
  private static final int THREADS = 4;
  private static final int DECISIONS_PER_THREAD = 200_000;

  private final PropagationTags.Factory factory = PropagationTags.factory();

  @Test
  void eachDecisionCarriesItsOwnRate() {
    for (double rate : RATES) {
      assertEquals(PTagsFactory.PTags.formatKnuthSamplingRate(rate), knuthSamplingRate(rate));
    }
  }

  @Test
  void noRateWithoutOne() {
    assertNull(knuthSamplingRate(Double.NaN));
  }

  /**
   * Concurrent decisions with different rates must each get their own rate. A cache whose rate and
   * value were published separately could pair one decision's rate with another's value.
   */
  @Test
  void concurrentDecisionsWithDifferentRatesDontMix() throws InterruptedException {
    AtomicReference<String> mismatch = new AtomicReference<>();
    CountDownLatch start = new CountDownLatch(1);
    Thread[] threads = new Thread[THREADS];
    for (int t = 0; t < THREADS; ++t) {
      int offset = t;
      threads[t] =
          new Thread(
              () -> {
                awaitQuietly(start);
                for (int i = 0; i < DECISIONS_PER_THREAD && mismatch.get() == null; ++i) {
                  double rate = RATES[(i + offset) % RATES.length];
                  String expected = PTagsFactory.PTags.formatKnuthSamplingRate(rate);
                  String actual = knuthSamplingRate(rate);
                  if (!expected.equals(actual)) {
                    mismatch.compareAndSet(null, "rate " + rate + " got _dd.p.ksr=" + actual);
                  }
                }
              });
      threads[t].start();
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    assertNull(mismatch.get());
  }

  private String knuthSamplingRate(double rate) {
    PropagationTags ptags = factory.empty();
    ptags.tryUpdateProbabilitySamplingDecision(SAMPLER_KEEP, AGENT_RATE, rate, false, 1L, false);
    CharSequence value = ptags.samplingState().getKnuthSamplingRate();
    return value == null ? null : value.toString();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
