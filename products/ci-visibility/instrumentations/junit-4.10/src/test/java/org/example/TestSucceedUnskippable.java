package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.experimental.categories.Category;

public class TestSucceedUnskippable {

  @Category(datadog_itr_unskippable.class)
  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  public interface datadog_itr_unskippable {}
}
