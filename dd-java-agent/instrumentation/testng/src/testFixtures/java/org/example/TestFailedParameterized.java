package org.example;

import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class TestFailedParameterized {

  @DataProvider(name = "dataProvider")
  public static Object[][] data() {
    return new Object[][] {
      {"hello", true},
      {"\"goodbye\"", false}
    };
  }

  @Test(dataProvider = "dataProvider")
  public void parameterized_test_succeed(final String str, final boolean booleanValue) {
    assertTrue(booleanValue);
  }
}
