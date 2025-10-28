package com.datadog.aiguard;

public abstract class AIGuardSystem {

  private AIGuardSystem() {}

  public static void start() {
    initializeSDK();
  }

  private static void initializeSDK() {
    AIGuardInternal.install();
  }
}
