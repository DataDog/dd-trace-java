package org.example;

import static org.junit.Assume.assumeTrue;

import org.junit.Test;

public class TestAssumption {
  @Test
  public void test_fail_assumption() {
    assumeTrue(1 > 2);
  }
}
