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

    assertTrue(budget.tryAcquire(60));
    assertTrue(budget.tryAcquire(30));

    assertEquals(90, budget.used());
  }

  @Test
  void admitsCostLargerThanTheRemainingHeadroom() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(90);

    // 10 left, 500 asked: headroom, not fit, decides
    assertTrue(budget.tryAcquire(500));

    assertEquals(590, budget.used());
  }

  @Test
  void admitsCostLargerThanTheWholeLimit() {
    AdmissionBudget budget = new AdmissionBudget(100);

    assertTrue(budget.tryAcquire(1_000));
  }

  @Test
  void refusesAtTheLimitWithoutCharging() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(100);

    assertFalse(budget.tryAcquire(1));

    assertEquals(100, budget.used());
  }

  @Test
  void releaseReopensAdmission() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(150);
    assertFalse(budget.tryAcquire(1));

    budget.release(150);

    assertEquals(0, budget.used());
    assertTrue(budget.tryAcquire(1));
  }

  @Test
  void cutoffBelowTheLimitShedsEarlier() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(50);

    assertFalse(budget.tryAcquire(1, 50));
    assertTrue(budget.tryAcquire(1, 51));
  }

  @Test
  void cutoffAboveTheLimitAdmitsPastIt() {
    AdmissionBudget budget = new AdmissionBudget(100);
    budget.tryAcquire(150);

    assertFalse(budget.tryAcquire(1));
    assertTrue(budget.tryAcquire(1, 200));
  }

  @Test
  void rejectsNonPositiveLimit() {
    assertThrows(IllegalArgumentException.class, () -> new AdmissionBudget(0));
    assertThrows(IllegalArgumentException.class, () -> new AdmissionBudget(-1));
  }
}
