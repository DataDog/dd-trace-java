package org.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

@RunWith(Suite.class)
@Suite.SuiteClasses({TestSuite.FirstTest.class, TestSuite.SecondTest.class})
public class TestSuite {
  public static class FirstTest {
    @Test
    public void testAddition() {
      assertEquals(5, 2 + 3);
    }
  }

  public static class SecondTest {
    @Test
    public void testSubtraction() {
      assertNotEquals(1, 5 - 3);
    }
  }
}
