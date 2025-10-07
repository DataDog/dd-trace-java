package org.example;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

public class TestAssumptionAndSucceed {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void test_fail_assumption() {
    assumeTrue(1 > 2);
  }
}
