package datadog.smoke;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class TestSucceedJunit5 {
  @Test
  public void test_succeed() {
    assertTrue(Calculator.subtract(2, 2) == 0);
  }
}
