package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.InstrumentationBridge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

public class TestSucceedUnskippable {

  @Test
  @Tags({@Tag(InstrumentationBridge.ITR_UNSKIPPABLE_TAG)})
  public void test_succeed() {
    assertTrue(true);
  }
}
