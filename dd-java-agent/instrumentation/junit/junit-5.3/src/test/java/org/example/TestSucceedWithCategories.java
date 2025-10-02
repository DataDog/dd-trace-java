package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("Slow"), @Tag("Flaky")})
public class TestSucceedWithCategories {

  @Test
  @Tags({@Tag("End2end"), @Tag("Browser")})
  public void test_succeed() {
    assertTrue(true);
  }
}
