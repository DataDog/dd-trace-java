package datadog.smoke;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.OrderWith;
import org.junit.runner.manipulation.Alphanumeric;

@OrderWith(Alphanumeric.class)
public class TestSucceedC {

  @Test
  public void test_succeed() {
    assertTrue(Calculator.add(2, 2) == 4);
  }

  @Test
  public void test_succeed_another() {
    assertTrue(Calculator.add(2, 2) == 4);
  }
}
