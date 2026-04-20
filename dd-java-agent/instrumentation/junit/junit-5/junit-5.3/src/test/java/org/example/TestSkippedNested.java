package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@Disabled("Ignore reason in class")
public class TestSkippedNested {

  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  @Nested
  class NestedSuite {
    @Test
    public void test_succeed_nested() {
      assertTrue(true);
    }
  }
}
