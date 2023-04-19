package org.example;

import static org.testng.Assert.assertTrue;

import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestSkippedClass {

  @BeforeClass
  public void setUp() {
    throw new SkipException("Ignore reason in class");
  }

  @Test
  public void test_class_skipped() {
    assertTrue(true);
  }

  @Test
  public void test_class_another_skipped() {
    assertTrue(true);
  }
}
