package datadog.trace.common.writer.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SpanBudgetTest {

  @TableTest({
    "scenario           | spans | cutoff",
    "single span        | 1     | 505   ",
    "small trace        | 10    | 550   ",
    "midsize trace      | 50    | 750   ",
    "large threshold    | 100   | 1000  ",
    "partial flush size | 1000  | 1000  "
  })
  void keptCutoffRisesWithTraceSize(int spans, long cutoff) {
    SpanBudget budget = SpanBudget.of(1000);

    assertEquals(cutoff, budget.keptCutoff(spans));
  }

  @Test
  void largeTraceWinsOverSmallOnesUnderPressure() {
    SpanBudget budget = SpanBudget.of(1000);
    assertTrue(budget.tryAdmitKept(600));

    assertFalse(budget.tryAdmitSampledOut(1));
    assertFalse(budget.tryAdmitKept(10));
    assertTrue(budget.tryAdmitKept(100));
  }

  @Test
  void sampledOutShedsFirst() {
    SpanBudget budget = SpanBudget.of(1000);
    assertTrue(budget.tryAdmitKept(250));

    assertFalse(budget.tryAdmitSampledOut(1));
    assertTrue(budget.tryAdmitKept(1));
  }

  @Test
  void forceKeptGetsPastTheLimit() {
    SpanBudget budget = SpanBudget.of(1000);
    assertTrue(budget.tryAdmitKept(1500));

    assertFalse(budget.tryAdmitKept(1000));
    assertTrue(budget.tryAdmitForceKept(1));
  }

  @Test
  void forceKeptStopsAtTwiceTheLimit() {
    SpanBudget budget = SpanBudget.of(1000);
    assertTrue(budget.tryAdmitForceKept(2000));

    assertFalse(budget.tryAdmitForceKept(1));
  }

  @Test
  void releaseReturnsTheSpans() {
    SpanBudget budget = SpanBudget.of(1000);
    budget.tryAdmitKept(700);

    budget.release(700);

    assertEquals(0, budget.used());
  }

  @Test
  void nonPositiveLimitDisablesTheBudget() {
    SpanBudget budget = SpanBudget.of(0);

    assertTrue(budget.isUnlimited());
    assertTrue(budget.tryAdmitSampledOut(Integer.MAX_VALUE));
    assertTrue(budget.tryAdmitSampledOut(Integer.MAX_VALUE));
    assertEquals(0, budget.used());
  }

  @Test
  void spansRefusedByTheBudgetAreNeverCounted() {
    SpanBudget budget = SpanBudget.of(100);
    assertTrue(budget.tryAdmitKept(100));

    for (int i = 0; i < 1_000; i++) {
      budget.tryAdmitKept(10);
      budget.tryAdmitSampledOut(10);
    }

    assertEquals(100, budget.used());
  }

  // Admission by headroom, not fit. A kept trace of LARGE_CHUNK_SPANS or more spans has the full
  // limit as its cutoff, so these exercise the limit itself.

  @Test
  void admitsWhileBelowTheLimitAndCountsEverySpan() {
    SpanBudget budget = SpanBudget.of(1000);

    assertTrue(budget.tryAdmitKept(600));
    assertTrue(budget.tryAdmitKept(300));

    assertEquals(900, budget.used());
  }

  @Test
  void admitsATraceLargerThanTheRemainingHeadroom() {
    SpanBudget budget = SpanBudget.of(1000);
    assertTrue(budget.tryAdmitKept(990));

    // 10 spans left, 500 asked: headroom, not fit, decides
    assertTrue(budget.tryAdmitKept(500));

    assertEquals(1490, budget.used());
  }

  @Test
  void admitsATraceLargerThanTheWholeLimit() {
    SpanBudget budget = SpanBudget.of(1000);

    assertTrue(budget.tryAdmitKept(5000));
  }

  @Test
  void refusesAtTheLimitWithoutCounting() {
    SpanBudget budget = SpanBudget.of(1000);
    assertTrue(budget.tryAdmitKept(1000));

    assertFalse(budget.tryAdmitKept(100));

    assertEquals(1000, budget.used());
  }

  @Test
  void releaseReopensAdmission() {
    SpanBudget budget = SpanBudget.of(1000);
    assertTrue(budget.tryAdmitKept(1500));
    assertFalse(budget.tryAdmitKept(100));

    budget.release(1500);

    assertEquals(0, budget.used());
    assertTrue(budget.tryAdmitKept(100));
  }
}
