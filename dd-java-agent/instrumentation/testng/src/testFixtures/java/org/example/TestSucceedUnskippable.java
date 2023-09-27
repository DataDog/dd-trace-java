package org.example;

import static org.testng.Assert.assertTrue;

import datadog.trace.api.civisibility.InstrumentationBridge;
import org.testng.annotations.Test;

@Test(groups = InstrumentationBridge.ITR_UNSKIPPABLE_TAG)
public class TestSucceedUnskippable {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }
}
