package com.datadog.profiling.utils;

import static datadog.environment.JavaVirtualMachine.getRuntimeVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersionBetween;

public class ExcludedVersions {

  public static void checkVersionExclusion() throws IllegalStateException {
    if (isVersionExcluded()) {
      throw new IllegalStateException("Excluded java version: " + getRuntimeVersion());
    }
  }

  public static boolean isVersionExcluded() {
    // Java 9 and 10 throw seg fault on MacOS if events are used in premain.
    // Since these versions are not LTS we just disable profiling events for them.
    if (isJavaVersion(9) || isJavaVersion(10)) {
      return true;
    }
    // Exclude <1.8.0_262, 1.8.0_282) due to https://bugs.openjdk.java.net/browse/JDK-8252904 (fixed
    // in 8u282)
    return isJavaVersionBetween(8, 0, 262, 8, 0, 282);
  }
}
