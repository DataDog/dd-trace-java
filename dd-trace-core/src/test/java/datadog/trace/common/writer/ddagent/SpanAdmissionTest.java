package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SpanAdmissionTest {

  @TableTest({
    "scenario           | spans | cutoff",
    "single span        | 1     | 505   ",
    "small trace        | 10    | 550   ",
    "midsize trace      | 50    | 750   ",
    "large threshold    | 100   | 1000  ",
    "partial flush size | 1000  | 1000  "
  })
  void keptCutoffRisesWithTraceSize(int spans, long cutoff) {
    SpanAdmission admission = SpanAdmission.of(1000);

    assertEquals(cutoff, admission.keptCutoff(spans));
  }

  @Test
  void largeTraceWinsOverSmallOnesUnderPressure() {
    SpanAdmission admission = SpanAdmission.of(1000);
    assertTrue(admission.tryAdmitKept(600));

    assertFalse(admission.tryAdmitSampledOut(1));
    assertFalse(admission.tryAdmitKept(10));
    assertTrue(admission.tryAdmitKept(100));
  }

  @Test
  void sampledOutShedsFirst() {
    SpanAdmission admission = SpanAdmission.of(1000);
    assertTrue(admission.tryAdmitKept(250));

    assertFalse(admission.tryAdmitSampledOut(1));
    assertTrue(admission.tryAdmitKept(1));
  }

  @Test
  void forceKeptGetsPastTheLimit() {
    SpanAdmission admission = SpanAdmission.of(1000);
    assertTrue(admission.tryAdmitKept(1500));

    assertFalse(admission.tryAdmitKept(1000));
    assertTrue(admission.tryAdmitForceKept(1));
  }

  @Test
  void forceKeptStopsAtTwiceTheLimit() {
    SpanAdmission admission = SpanAdmission.of(1000);
    assertTrue(admission.tryAdmitForceKept(2000));

    assertFalse(admission.tryAdmitForceKept(1));
  }

  @Test
  void releaseReturnsTheSpans() {
    SpanAdmission admission = SpanAdmission.of(1000);
    admission.tryAdmitKept(700);

    admission.release(700);

    assertEquals(0, admission.used());
  }

  @Test
  void nonPositiveLimitDisablesTheBudget() {
    SpanAdmission admission = SpanAdmission.of(0);

    assertTrue(admission.isUnlimited());
    assertTrue(admission.tryAdmitSampledOut(Integer.MAX_VALUE));
    assertTrue(admission.tryAdmitSampledOut(Integer.MAX_VALUE));
    assertEquals(0, admission.used());
  }
}
