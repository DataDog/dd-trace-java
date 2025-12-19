package org.example;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class TestSkipped {

  @Disabled("Ignore reason in test")
  @Test
  public void test_skipped() {}
}
