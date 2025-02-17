package datadog.smoke;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestSucceeded {

  @Test
  public void test_succeeded() {
    assertTrue(Calculator.add(2, 2) == 4);
  }
}
