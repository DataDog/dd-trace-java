package org.example;

import static org.testng.AssertJUnit.assertEquals;

import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/** Inspired by a real-world example */
public class TestParameterizedModifiesParams {

  @DataProvider(name = "dataProvider")
  public static Object[][] data() {
    return new Object[][] {{"I will modify this set", new HashSet<>()}};
  }

  @Test(dataProvider = "dataProvider")
  public void parameterized_test_succeed(final String str, final Set<String> set) {
    set.add("why not");
    assertEquals(1, set.size());
  }
}
