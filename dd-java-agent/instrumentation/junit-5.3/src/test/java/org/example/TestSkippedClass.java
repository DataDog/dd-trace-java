package org.example;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("Ignore reason in class")
public class TestSkippedClass {

  @Test
  public void test_class_skipped() {}
}
