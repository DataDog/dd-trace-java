package datadog.smoke;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TestSucceed {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void test_to_skip_with_itr() {
    assertTrue(true);
  }
}
