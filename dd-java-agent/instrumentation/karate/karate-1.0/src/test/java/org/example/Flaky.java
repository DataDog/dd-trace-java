package org.example;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class Flaky {

  private static int counter = 0;
  private static int stepCounter = 0;

  // Fails the first two attempts, passes from the third onwards.
  public static void flake() {
    assertTrue(++counter >= 3);
  }

  // Same flaky behavior exposed as a value, for continueOnStepFailure scenarios that assert it in
  // several steps. Uses a separate counter because both helpers run in the same test JVM.
  public static boolean shouldPass() {
    return ++stepCounter >= 3;
  }
}
