package datadog.smoke;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChildTest extends BaseTest {

  @Override
  public void setUp() {
    super.setUp();
  }

  @Override
  public void tearDown() {
    super.tearDown();
  }

  @Override
  public void test_inherited() {
    assertEquals("ready", fixture);
  }

  @Test
  public void test_uses_fixture() {
    assertEquals("ready", fixture);
  }
}
