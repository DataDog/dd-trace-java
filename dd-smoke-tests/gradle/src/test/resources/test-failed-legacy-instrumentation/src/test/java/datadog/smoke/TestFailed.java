package datadog.smoke;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestFailed {

  @Test
  public void test_failed() {
    assertTrue(false);
  }
}
