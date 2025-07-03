package com.datadog.profiling.controller;

import static datadog.environment.JavaVirtualMachine.isJavaVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.environment.JavaVirtualMachine.isOracleJDK8;

public class ProfilingSupport {

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
