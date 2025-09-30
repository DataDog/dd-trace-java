package org.example;

import org.junit.AssumptionViolatedException;
import org.junit.jupiter.api.Test;

public class TestAssumptionLegacy {

  @Test
  public void test_fail_assumption_legacy() {
    throw new AssumptionViolatedException("assumption is not fulfilled");
  }
}
