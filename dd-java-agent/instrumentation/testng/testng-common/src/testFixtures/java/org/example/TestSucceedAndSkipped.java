package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.SkipException;
import org.testng.annotations.Test;

public class TestSucceedAndSkipped {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Test
  public void test_skipped() {
    throw new SkipException("Ignore reason in test");
  }
}
