package datadog.smoke;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TestSucceedPropertyAssertion {

  @Test
  public void test_succeed() {
    assertEquals("provided-via-command-line", System.getProperty("my-custom-property"));
  }

}
