package org.example;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({Slow.class, Flaky.class})
public class TestSucceedWithCategories {

  @Category({End2End.class, Browser.class})
  @Test
  public void test_succeed() {
    assertTrue(true);
  }
}

class End2End {}

class Browser {}

class Slow {}

class Flaky {}
