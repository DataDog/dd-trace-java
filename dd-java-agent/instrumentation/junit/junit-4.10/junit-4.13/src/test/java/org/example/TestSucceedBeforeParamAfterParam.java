package org.example;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestSucceedBeforeParamAfterParam {
  private final int num1;
  private final int num2;
  private final int sum;

  public TestSucceedBeforeParamAfterParam(final int num1, final int num2, final int sum) {
    this.num1 = num1;
    this.num2 = num2;
    this.sum = sum;
  }

  @Parameterized.BeforeParam
  public static void setup() {}

  @Parameterized.AfterParam
  public static void tearDown() {}

  @Parameterized.Parameters
  public static Collection<Object[]> data() {
    return Arrays.asList(new Object[][] {{0, 0, 0}, {1, 1, 2}});
  }

  @Test
  public void parameterized_test_succeed() {
    assertEquals(num1 + num2, sum);
  }
}
