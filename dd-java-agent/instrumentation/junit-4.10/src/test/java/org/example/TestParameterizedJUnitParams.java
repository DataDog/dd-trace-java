package org.example;

import static org.junit.Assert.assertEquals;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class TestParameterizedJUnitParams {

  @Test
  @Parameters({"1, 2, 3", "2, 2, 4"})
  public void test_parameterized(int a, int b, int expectedValue) {
    assertEquals(expectedValue, a + b);
  }
}
