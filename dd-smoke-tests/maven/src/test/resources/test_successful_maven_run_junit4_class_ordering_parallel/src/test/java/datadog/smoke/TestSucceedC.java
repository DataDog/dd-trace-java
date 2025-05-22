package datadog.smoke;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestSucceedC {

  @Test
  public void test_succeed() {
    assertTrue(Calculator.add(2, 2) == 4);
  }
}
