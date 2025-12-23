package org.example;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

public class TestSucceedDataProvider {

  private final int param;

  @Factory(dataProvider = "dataMethod")
  public TestSucceedDataProvider(int param) {
    this.param = param;
  }

  @DataProvider
  public static Object[][] dataMethod() {
    return new Object[][] {{0}};
  }

  @Test
  public void testMethod() {
    assertEquals(param, 0);
  }
}
