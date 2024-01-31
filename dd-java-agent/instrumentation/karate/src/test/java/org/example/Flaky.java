package org.example;

import org.junit.Assert;

public class Flaky {

  private static int counter = 0;

  public static void flake() {
    Assert.assertTrue(++counter >= 3);
  }
}
