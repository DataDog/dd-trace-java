package datadog.smoke;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestSucceedA {

  @Test
  public void test_succeed() {
    assertTrue(Calculator.add(2, 2) == 4);
  }
}
