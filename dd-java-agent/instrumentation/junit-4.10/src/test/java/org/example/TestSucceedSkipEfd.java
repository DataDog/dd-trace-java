package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.experimental.categories.Category;

public class TestSucceedSkipEfd {

  @Category(datadog_efd_disable.class)
  @Test
  public void test_succeed() {
    assertTrue(true);
  }

  public interface datadog_efd_disable {}
}
