package datadog.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestSucceed {

  @Test
  public void test_succeed() {
    assertTrue(Calculator.add(2, 2) == 4);
  }

  @Test
  public void test_to_skip_with_itr() {
    assertTrue(Calculator.subtract(3, 2) == 1);
  }
}
