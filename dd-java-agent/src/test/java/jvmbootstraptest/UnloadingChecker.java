package jvmbootstraptest;

import datadog.trace.test.util.GCUtils;

public class UnloadingChecker {
  public static void main(final String[] args) {
    try {
      GCUtils.awaitGC();
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
