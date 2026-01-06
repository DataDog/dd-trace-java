package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class Flaky {

  private static int counter = 0;

  public static void flake() {
    assertTrue(++counter >= 3);
  }
}
