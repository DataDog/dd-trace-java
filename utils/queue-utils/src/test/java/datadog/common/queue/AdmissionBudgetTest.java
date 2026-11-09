package datadog.common.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdmissionBudgetTest {

  @Test
  void admitsWhileBelowTheLimitAndChargesTheFullCost() {
    AdmissionBudget budget = new AdmissionBudget(100);

    assertTrue(budget.tryAcquire(60, budget.limit()));
    assertTrue(budget.tryAcquire(30, budget.limit()));

    assertEquals(90, budget.used());
  }

  @Test
  void admitsCostLargerThanTheRemainingHeadroom() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(90, budget.limit());

    // 10 left, 500 asked: headroom, not fit, decides
    assertTrue(budget.tryAcquire(500, budget.limit()));

    assertEquals(590, budget.used());
  }

  @Test
  void admitsCostLargerThanTheWholeLimit() {
    AdmissionBudget budget = new AdmissionBudget(100);

    assertTrue(budget.tryAcquire(1_000, budget.limit()));
  }

  @Test
  void refusesAtTheLimitWithoutCharging() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(100, budget.limit());

    assertFalse(budget.tryAcquire(1, budget.limit()));

    assertEquals(100, budget.used());
  }

  @Test
  void releaseReopensAdmission() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(150, budget.limit());
    assertFalse(budget.tryAcquire(1, budget.limit()));

    budget.release(150);

    assertEquals(0, budget.used());
    assertTrue(budget.tryAcquire(1, budget.limit()));
  }

  @Test
  void cutoffBelowTheLimitShedsEarlier() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(50, budget.limit());

    assertFalse(budget.tryAcquire(1, 50));
    assertTrue(budget.tryAcquire(1, 51));
  }

  @Test
  void cutoffAboveTheLimitAdmitsPastIt() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(150, budget.limit());

    assertFalse(budget.tryAcquire(1, budget.limit()));
    assertTrue(budget.tryAcquire(1, 200));
  }

  @Test
  void rejectsNonPositiveLimit() {
    assertThrows(IllegalArgumentException.class, () -> new AdmissionBudget(0));
    assertThrows(IllegalArgumentException.class, () -> new AdmissionBudget(-1));
  }
}
