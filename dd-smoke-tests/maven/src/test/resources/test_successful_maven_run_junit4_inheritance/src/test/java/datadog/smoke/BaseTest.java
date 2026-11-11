package datadog.smoke;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public abstract class BaseTest {

  protected String fixture;

  @Before
  public void setUp() {
    fixture = "ready";
  }

  @After
  public void tearDown() {
    fixture = null;
  }

  @Test
  public void test_inherited() {}
}
