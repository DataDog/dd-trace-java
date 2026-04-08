package com.datadog.profiling.controller;

import static datadog.environment.JavaVirtualMachine.isJavaVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.environment.JavaVirtualMachine.isOracleJDK8;

import datadog.environment.JavaVirtualMachine;

public class ProfilingSupport {

  /**
   * Checks whether jmethodID handling is safe on the current JVM version. Unsafe versions can cause
   * crashes due to <a href="https://bugs.openjdk.org/browse/JDK-8313816">JDK-8313816</a>.
   */
  public static boolean isJmethodIDSafe() {
    if (isJavaVersionAtLeast(22)) {
      return true;
    }
    switch (JavaVirtualMachine.getLangVersion()) {
      case "8":
        return true;
      case "11":
        return isJavaVersionAtLeast(11, 0, 23);
      case "17":
        return isJavaVersionAtLeast(17, 0, 11);
      case "21":
        return isJavaVersionAtLeast(21, 0, 3);
      default:
        return false;
    }
  }

  /**
   * Checks whether any live heap profiling mechanism is safe to enable on the current platform.
   * Returns true if at least one of ddprof native (jmethodID safe) or JFR OldObjectSample is
   * available.
   */
  public static boolean isLiveHeapProfilingSafe() {
    return isJmethodIDSafe() || isOldObjectSampleAvailable();
  }

  public static boolean isOldObjectSampleAvailable() {
    if (isOracleJDK8()) {
      return false;
    }

    return (isJavaVersion(11) && isJavaVersionAtLeast(11, 0, 12))
        || (isJavaVersion(15) && isJavaVersionAtLeast(15, 0, 4))
        || (isJavaVersion(16) && isJavaVersionAtLeast(16, 0, 2))
        || (isJavaVersion(17) && isJavaVersionAtLeast(17, 0, 3))
        || isJavaVersionAtLeast(18);
  }

  public static boolean isObjectAllocationSampleAvailable() {
    if (isOracleJDK8()) {
      return false;
    }

    return isJavaVersionAtLeast(16);
  }

  public static boolean isObjectCountParallelized() {
    // parallelized jdk.ObjectCount implemented in JDK21 and backported to JDK17
    // https://bugs.openjdk.org/browse/JDK-8307348
    return (isJavaVersion(17) && isJavaVersionAtLeast(17, 0, 9)) || isJavaVersionAtLeast(21);
  }

  public static boolean isNativeMethodSampleAvailable() {
    if (isOracleJDK8()) {
      return false;
    }

    return (isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 302)) || isJavaVersionAtLeast(11);
  }

  public static boolean isFileWriteDurationCorrect() {
    return isJavaVersion(8)
        || isJavaVersion(11)
        || isJavaVersionAtLeast(17, 0, 6)
        || isJavaVersionAtLeast(19);
  }
}
