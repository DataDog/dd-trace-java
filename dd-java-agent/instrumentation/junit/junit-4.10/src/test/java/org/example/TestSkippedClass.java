package org.example;

import org.junit.Ignore;
import org.junit.Test;

@Ignore("Ignore reason in class")
public class TestSkippedClass {

  @Test
  public void test_class_skipped() {}

  @Test
  public void test_class_another_skipped() {}
}
