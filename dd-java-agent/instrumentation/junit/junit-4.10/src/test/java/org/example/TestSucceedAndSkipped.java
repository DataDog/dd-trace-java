package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Ignore;
import org.junit.Test;

public class TestSucceedAndSkipped {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Ignore("Ignore reason in test")
  @Test
  public void test_skipped() {}
}
