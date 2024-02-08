package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestFailedParameterized {

  @Parameterized.Parameters(name = "{0} {1} {2}")
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {{0, 0, 42}, {21, 21, 42}});
  }

  private final int a;
  private final int b;
  private final int c;

  public TestFailedParameterized(int a, int b, int c) {
    this.a = a;
    this.b = b;
    this.c = c;
  }

  @Test
  public void test_failed_parameterized() {
    assertEquals(a + b, c);
  }
}
