package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

public class TestSkippedDependency {

  @Test
  public void test_failed() {
    assertTrue(false);
  }

  @Test(dependsOnMethods = "test_failed")
  public void test_skipped_dependency() {
    assertTrue(true);
  }
}
