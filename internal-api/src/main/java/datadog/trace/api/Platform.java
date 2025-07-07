package datadog.trace.api;

import static datadog.environment.JavaVirtualMachine.isJ9;
import static datadog.environment.JavaVirtualMachine.isJavaVersion;
import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;
import static datadog.environment.JavaVirtualMachine.isOracleJDK8;

/**
 * This class is used early on during premain; it must not touch features like JMX or JUL in case
 * they trigger early loading/binding.
 */
public final class Platform {
  // A helper class to capture whether the executable is a native image or not.
  // This class needs to be iniatlized at build only during the AOT compilation and build.
  private static class Captured {
    public static final boolean isNativeImage = checkForNativeImageBuilder();
  }

  private static final boolean HAS_JFR = checkForJfr();
  private static final boolean IS_NATIVE_IMAGE_BUILDER = checkForNativeImageBuilder();
  private static final boolean IS_NATIVE_IMAGE = Captured.isNativeImage;

  public static boolean hasJfr() {
    return HAS_JFR;
  }

  public static boolean isNativeImageBuilder() {
    return IS_NATIVE_IMAGE_BUILDER;
  }

  public static boolean isNativeImage() {
    return IS_NATIVE_IMAGE;
  }

  private static boolean checkForJfr() {
    try {
      /* Check only for the open-sources JFR implementation.
       * If it is ever needed to support also the closed sourced JDK 8 version the check should be
       * enhanced.
       * Note: we need to hardcode the good-known-versions instead of probing for JFR classes to
       *       make this work with GraalVM native image.
       * Note: as of version 0.49.0 of J9 the JVM contains JFR classes, but it is not fully functional
       */
      return ((isJavaVersion(8) && isJavaVersionAtLeast(8, 0, 272) || (isJavaVersionAtLeast(11))))
          && !isJ9()
          && !isOracleJDK8();
    } catch (Throwable e) {
      return false;
    }
  }

  private static boolean checkForNativeImageBuilder() {
    try {
      return "org.graalvm.nativeimage.builder".equals(System.getProperty("jdk.module.main"));
    } catch (Throwable e) {
      return false;
    }
  }
}
