package org.example;

import org.testng.SkipException;
import org.testng.annotations.Test;

public class TestSkipped {
  @Test
  public void test_skipped() {
    throw new SkipException("Ignore reason in test");
  }
}
