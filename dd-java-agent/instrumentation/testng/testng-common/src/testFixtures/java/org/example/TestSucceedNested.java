package org.example;

import static org.testng.Assert.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.testng.annotations.Test;

public class TestSucceedNested {

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
