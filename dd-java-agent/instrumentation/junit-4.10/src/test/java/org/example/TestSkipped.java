package org.example;

import org.junit.Ignore;
import org.junit.Test;

public class TestSkipped {

  @Ignore("Ignore reason in test")
  @Test
  public void test_skipped() {}
}
