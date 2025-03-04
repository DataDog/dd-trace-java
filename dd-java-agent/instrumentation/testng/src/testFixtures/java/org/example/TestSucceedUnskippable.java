package org.example;

import static org.testng.Assert.assertTrue;

import datadog.trace.api.civisibility.CIConstants;
import org.testng.annotations.Test;

@Test(groups = CIConstants.Tags.ITR_UNSKIPPABLE_TAG)
public class TestSucceedUnskippable {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }
}
