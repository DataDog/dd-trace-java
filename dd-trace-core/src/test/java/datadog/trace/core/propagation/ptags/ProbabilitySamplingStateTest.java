package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.PropagationTags.SamplingState;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class ProbabilitySamplingStateTest {
  private static final long TRACE_ID_LOW_ORDER_BITS = 0xfff972474538efffL;

  private final PropagationTags.Factory factory = PropagationTags.factory();

  @TableTest({
    "scenario | priority     | rate",
    "keep     | SAMPLER_KEEP | 0.1 ",
    "drop     | SAMPLER_DROP | 0.1 ",
    "always   | SAMPLER_KEEP | 1.0 ",
    "never    | SAMPLER_DROP | 0.0 "
  })
  void freshDecisionBuildsSameOtelStateAsEagerConversion(String priority, double rate) {
    int samplingPriority = "SAMPLER_KEEP".equals(priority) ? SAMPLER_KEEP : SAMPLER_DROP;
    PropagationTags ptags = factory.empty();

    ptags.tryUpdateProbabilitySamplingDecision(
        samplingPriority, AGENT_RATE, rate, false, TRACE_ID_LOW_ORDER_BITS, false);

    SamplingState state = ptags.samplingState();
    assertInstanceOf(ProbabilitySamplingState.class, state);
    assertEquals(
        OtelTraceState.fromProbabilityDecision(TRACE_ID_LOW_ORDER_BITS, rate, samplingPriority)
            .toString(),
        state.getOtelTraceState().toString());
  }

  @Test
  void repeatedReadsReturnOneInstance() {
    PropagationTags ptags = factory.empty();
    ptags.tryUpdateProbabilitySamplingDecision(
        SAMPLER_KEEP, AGENT_RATE, 0.1, false, TRACE_ID_LOW_ORDER_BITS, false);

    SamplingState state = ptags.samplingState();

    assertSame(state.getOtelTraceState(), state.getOtelTraceState());
  }

  /**
   * Readers racing to build the state agree on one instance. A stress test can't force the race, so
   * this is a smoke test of the compare-and-set: it can't fail while that holds.
   */
  @Test
  void racingReadsReturnOneInstance() throws InterruptedException {
    int readers = 8;
    for (int trial = 0; trial < 100; trial++) {
      PropagationTags ptags = factory.empty();
      ptags.tryUpdateProbabilitySamplingDecision(
          SAMPLER_KEEP, AGENT_RATE, 0.1, false, TRACE_ID_LOW_ORDER_BITS, false);
      SamplingState state = ptags.samplingState();

      CharSequence[] seen = new CharSequence[readers];
      CountDownLatch start = new CountDownLatch(1);
      Thread[] threads = new Thread[readers];
      for (int i = 0; i < readers; i++) {
        int reader = i;
        threads[i] =
            new Thread(
                () -> {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  seen[reader] = state.getOtelTraceState();
                });
        threads[i].start();
      }
      start.countDown();
      for (Thread thread : threads) {
        thread.join();
      }

      for (CharSequence otelTraceState : seen) {
        assertSame(seen[0], otelTraceState);
      }
      assertSame(seen[0], state.getOtelTraceState());
    }
  }

  @Test
  void rateLimiterRejectionRecordsNoOtelState() {
    PropagationTags ptags = factory.empty();
    ptags.tryUpdateProbabilitySamplingDecision(
        SAMPLER_DROP, AGENT_RATE, 0.1, true, TRACE_ID_LOW_ORDER_BITS, false);

    SamplingState state = ptags.samplingState();

    assertEquals(SamplingState.class, state.getClass());
    assertNull(state.getOtelTraceState());
  }

  @Test
  void laterDecisionStartsFromDeferredOtelState() {
    PropagationTags ptags = factory.empty();
    ptags.tryUpdateProbabilitySamplingDecision(
        SAMPLER_KEEP, AGENT_RATE, 0.1, false, TRACE_ID_LOW_ORDER_BITS, false);

    ptags.forceKeep(MANUAL);

    // A locally made probability state has nothing inherited to keep, so a manual keep drops it
    assertNull(
        OtelTraceState.fromProbabilityDecision(TRACE_ID_LOW_ORDER_BITS, 0.1, SAMPLER_KEEP)
            .forNonProbabilityDecision());
    assertNull(ptags.samplingState().getOtelTraceState());
  }
}
