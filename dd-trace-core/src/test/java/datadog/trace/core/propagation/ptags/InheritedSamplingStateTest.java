package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.core.propagation.ptags.InheritedSamplingState.NO_CHECKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class InheritedSamplingStateTest {
  private static final int ORIGINAL_SIZE = 40;

  /**
   * Parsing on first read with pending checks gives what parsing on extract and checking at each
   * step gave, for every combination of checks and in either order.
   */
  @TableTest({
    "scenario                    | raw                                       ",
    "keep consistent             | 'rv:ef284ace7a91e1;th:e6666666666668'     ",
    "drop consistent             | 'rv:0000000000000a;th:8'                  ",
    "random value only           | 'rv:ef284ace7a91e1'                       ",
    "threshold only              | 'th:8'                                    ",
    "unknown field kept          | 'rv:0000000000000a;th:8;future:value'     ",
    "threshold and unknown field | 'th:8;future:value'                       ",
    "unknown fields only         | 'simplefield;blah;foo'                    ",
    "invalid random value        | 'rv:zz;th:8;future:value'                 ",
    "duplicate random value      | 'rv:ef284ace7a91e1;rv:0000000000000a;th:8'",
    "nothing valid               | 'rv:invalid;th:invalid'                   "
  })
  void resolvesAsEagerParseThenChecks(String raw) {
    for (boolean checkSampled : new boolean[] {false, true}) {
      for (boolean checkNotSampled : new boolean[] {false, true}) {
        byte checks = NO_CHECKS;
        if (checkSampled) {
          checks = InheritedSamplingState.withCheck(checks, true);
        }
        if (checkNotSampled) {
          checks = InheritedSamplingState.withCheck(checks, false);
        }
        InheritedSamplingState state = state(raw, checks);

        String sampledFirst = eager(raw, checkSampled, checkNotSampled, true);
        String notSampledFirst = eager(raw, checkSampled, checkNotSampled, false);
        assertEquals(sampledFirst, notSampledFirst, "check order mustn't matter");
        assertEquals(sampledFirst, String.valueOf(state.getOtelTraceState()));
      }
    }
  }

  @Test
  void parsesOnlyOnFirstRead() {
    InheritedSamplingState state = state("rv:ef284ace7a91e1;th:e6666666666668", NO_CHECKS);

    assertFalse(state.isResolved());
    state.getOtelTraceState();
    assertTrue(state.isResolved());
  }

  @Test
  void repeatedReadsReturnOneInstance() {
    InheritedSamplingState state = state("rv:ef284ace7a91e1;th:e6666666666668", NO_CHECKS);

    assertSame(state.getOtelTraceState(), state.getOtelTraceState());
  }

  @Test
  void keepsOriginalSizeForTheHeaderEstimate() {
    InheritedSamplingState state = state("rv:ef284ace7a91e1;th:e6666666666668", NO_CHECKS);

    assertEquals(ORIGINAL_SIZE, ((OtelTraceState) state.getOtelTraceState()).getOriginalSize());
  }

  private static InheritedSamplingState state(String raw, byte checks) {
    return new InheritedSamplingState(SAMPLER_KEEP, null, null, raw, ORIGINAL_SIZE, checks);
  }

  /** What extraction did before deferring: parse, then check at each step. */
  private static String eager(
      String raw, boolean checkSampled, boolean checkNotSampled, boolean sampledFirst) {
    OtelTraceState state = OtelTraceState.parse(raw, ORIGINAL_SIZE);
    if (sampledFirst) {
      state = checkSampled ? check(state, true) : state;
      state = checkNotSampled ? check(state, false) : state;
    } else {
      state = checkNotSampled ? check(state, false) : state;
      state = checkSampled ? check(state, true) : state;
    }
    return String.valueOf(state);
  }

  private static OtelTraceState check(OtelTraceState state, boolean sampled) {
    return state == null || state.isConsistentWith(sampled) ? state : state.withoutThreshold();
  }
}
