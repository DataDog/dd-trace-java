package org.example;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

public class TestAssumption {

  @Test
  public void test_fail_assumption() {
    assumeTrue(1 > 2);
  }
}
