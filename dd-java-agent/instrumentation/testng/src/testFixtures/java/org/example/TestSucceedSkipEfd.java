package org.example;

import static org.testng.Assert.assertTrue;

import datadog.trace.api.civisibility.CIConstants;
import org.testng.annotations.Test;

public class TestSucceedSkipEfd {

  @Test(groups = CIConstants.Tags.EFD_DISABLE_TAG)
  public void test_succeed() {
    assertTrue(true);
  }
}
