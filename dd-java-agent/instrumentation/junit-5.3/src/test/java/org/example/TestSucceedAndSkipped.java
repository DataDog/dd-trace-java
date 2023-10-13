package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class TestSucceedAndSkipped {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Disabled("Ignore reason in test")
  @Test
  public void test_skipped() {}
}
