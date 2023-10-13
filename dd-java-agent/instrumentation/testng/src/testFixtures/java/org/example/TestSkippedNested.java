package org.example;

import static org.testng.Assert.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestSkippedNested {

  @BeforeClass
  public void setUp() {
    throw new SkipException("Ignore reason in class");
  }

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  public class NestedSuite {
    @Test
    public void test_succeed_nested() {
      Assertions.assertTrue(true);
    }
  }
}
