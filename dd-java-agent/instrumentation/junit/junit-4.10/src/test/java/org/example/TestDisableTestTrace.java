package org.example;

import static org.junit.Assert.assertTrue;

import datadog.trace.api.DisableTestTrace;
import org.junit.Test;

@DisableTestTrace
public class TestDisableTestTrace {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }
}
