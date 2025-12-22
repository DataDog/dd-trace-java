package org.example;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestParameterized {

  @Parameterized.Parameters(name = "{1}")
  public static Collection<Object[]> data() {
    return Arrays.asList(
        new Object[][] {{new ParamObject(), "str1", 0}, {new ParamObject(), "\"str2\"", 1}});
  }

  public TestParameterized(final ParamObject param1, final String param2, final int param3) {}

  @Test
  public void parameterized_test_succeed() {
    assertTrue(true);
  }

  private static class ParamObject {}
}
