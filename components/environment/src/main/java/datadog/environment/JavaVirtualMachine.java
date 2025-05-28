package datadog.environment;

import static datadog.environment.CommandLine.VM_ARGUMENTS;

import java.util.List;

public class JavaVirtualMachine {
  private static final JavaVersion JAVA_VERSION = JavaVersion.getRuntimeVersion();
  private static final Runtime RUNTIME = new Runtime();

  public static boolean isJavaVersion(int major) {
    return JAVA_VERSION.is(major);
  }

  public static boolean isJavaVersion(int major, int minor) {
    return JAVA_VERSION.is(major, minor);
  }

  public static boolean isJavaVersion(int major, int minor, int update) {
    return JAVA_VERSION.is(major, minor, update);
  }

  public static boolean isJavaVersionAtLeast(int major) {
    return isJavaVersionAtLeast(major, 0, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor) {
    return isJavaVersionAtLeast(major, minor, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor, int update) {
    return JAVA_VERSION.isAtLeast(major, minor, update);
  }

  /**
   * Checks if the Java version is between {@code fromMajor} (inclusive) and {@code toMajor}
   * (exclusive).
   *
   * @param fromMajor major from version (inclusive)
   * @param toMajor major to version (exclusive)
   * @return if the current java version is between the from version (inclusive) and the to version
   *     exclusive
   */
  public static boolean isJavaVersionBetween(int fromMajor, int toMajor) {
    return isJavaVersionBetween(fromMajor, 0, toMajor, 0);
  }

  /**
   * Checks if the Java version is between {@code fromMajor.fromMinor} (inclusive) and {@code
   * toMajor.toMinor} (exclusive).
   *
   * @param fromMajor major from version (inclusive)
   * @param fromMinor minor from version (inclusive)
   * @param toMajor major to version (exclusive)
   * @param toMinor minor to version (exclusive)
   * @return if the current java version is between the from version (inclusive) and the to version
   *     exclusive
   */
  public static boolean isJavaVersionBetween(
      int fromMajor, int fromMinor, int toMajor, int toMinor) {
    return isJavaVersionBetween(fromMajor, fromMinor, 0, toMajor, toMinor, 0);
  }

  /**
   * Checks if the Java version is between {@code fromMajor.fromMinor.fromUpdate} (inclusive) and
   * {@code toMajor.toMinor.toUpdate} (exclusive).
   *
   * @param fromMajor major from version (inclusive)
   * @param fromMinor minor from version (inclusive)
   * @param fromUpdate update from version (inclusive)
   * @param toMajor major to version (exclusive)
   * @param toMinor minor to version (exclusive)
   * @param toUpdate update to version (exclusive)
   * @return if the current java version is between the from version (inclusive) and the to version
   *     exclusive
   */
  public static boolean isJavaVersionBetween(
      int fromMajor, int fromMinor, int fromUpdate, int toMajor, int toMinor, int toUpdate) {
    return JAVA_VERSION.isBetween(fromMajor, fromMinor, fromUpdate, toMajor, toMinor, toUpdate);
  }

  /**
   * Checks whether the current JVM is an Oracle JDK 8.
   *
   * @return {@code true} if the current JVM is an Oracle JDK 8, {@code false} otherwise.
   */
  public static boolean isOracleJDK8() {
    return isJavaVersion(8)
        && RUNTIME.vendor.contains("Oracle")
        && !RUNTIME.name.contains("OpenJDK");
  }

  public static boolean isJ9() {
    return System.getProperty("java.vm.name").contains("J9");
  }

  public static boolean isIbm8() {
    return isJavaVersion(8) && RUNTIME.vendor.contains("IBM");
  }

  public static boolean isGraalVM() {
    return RUNTIME.vendorVersion.toLowerCase().contains("graalvm");
  }

  public static String getLangVersion() {
    return String.valueOf(JAVA_VERSION.major);
  }

  public static String getRuntimeVendor() {
    return RUNTIME.vendor;
  }

  public static String getRuntimeVersion() {
    return RUNTIME.version;
  }

  public static String getRuntimePatches() {
    return RUNTIME.patches;
  }

  /**
   * Gets the JVM CLI arguments.
   *
   * @return The JVM CLI arguments, an empty collection if they can't be retrieved.
   */
  public static List<String> getVmArguments() {
    return VM_ARGUMENTS;
  }

  static final class Runtime {
    /*
     * Example:
     *    jvm     -> "AdoptOpenJDK 1.8.0_265-b01"
     *
     *    name    -> "OpenJDK"
     *    vendor  -> "AdoptOpenJDK"
     *    version -> "1.8.0_265"
     *    patches -> "b01"
     */
    public final String name;

    public final String vendor;
    public final String version;
    public final String vendorVersion;
    public final String patches;

    public Runtime() {
      this(
          System.getProperty("java.version"),
          System.getProperty("java.runtime.version"),
          System.getProperty("java.runtime.name"),
          System.getProperty("java.vm.vendor"),
          System.getProperty("java.vendor.version"));
    }

    // Only visible for testing
    Runtime(String javaVer, String rtVer, String name, String vendor, String vendorVersion) {
      this.name = name == null ? "" : name;
      this.vendor = vendor == null ? "" : vendor;
      javaVer = javaVer == null ? "" : javaVer;
      this.version = javaVer;
      this.vendorVersion = vendorVersion == null ? "" : vendorVersion;
      rtVer = javaVer.isEmpty() || rtVer == null ? javaVer : rtVer;
      int patchStart = javaVer.length() + 1;
      this.patches = (patchStart >= rtVer.length()) ? "" : rtVer.substring(javaVer.length() + 1);
    }
  }
}
