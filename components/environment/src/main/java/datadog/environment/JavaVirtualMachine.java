package datadog.environment;

import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;

public final class JavaVirtualMachine {
  private static final CommandLine commandLine = new CommandLine();
  private static final JavaVersion javaVersion = JavaVersion.getRuntimeVersion();
  private static final Runtime runtime = new Runtime();

  private JavaVirtualMachine() {}

  public static boolean isJavaVersion(int major) {
    return javaVersion.is(major);
  }

  public static boolean isJavaVersion(int major, int minor) {
    return javaVersion.is(major, minor);
  }

  public static boolean isJavaVersion(int major, int minor, int update) {
    return javaVersion.is(major, minor, update);
  }

  public static boolean isJavaVersionAtLeast(int major) {
    return isJavaVersionAtLeast(major, 0, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor) {
    return isJavaVersionAtLeast(major, minor, 0);
  }

  public static boolean isJavaVersionAtLeast(int major, int minor, int update) {
    return javaVersion.isAtLeast(major, minor, update);
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
    return javaVersion.isBetween(fromMajor, fromMinor, fromUpdate, toMajor, toMinor, toUpdate);
  }

  /**
   * Checks whether the current JVM is an Oracle JDK 8.
   *
   * @return {@code true} if the current JVM is an Oracle JDK 8, {@code false} otherwise.
   */
  public static boolean isOracleJDK8() {
    return isJavaVersion(8)
        && runtime.vendor.contains("Oracle")
        && !runtime.name.contains("OpenJDK");
  }

  public static boolean isHotspot() {
    String prop = SystemProperties.getOrDefault("java.vm.name", "");
    if (prop.isEmpty()) {
      return false;
    }
    return prop.contains("OpenJDK")
        || prop.contains("HotSpot")
        || prop.contains("GraalVM")
        || prop.contains("Dynamic Code Evolution");
  }

  public static boolean isJ9() {
    return SystemProperties.getOrDefault("java.vm.name", "").contains("J9");
  }

  public static boolean isIbm() {
    return runtime.vendor.contains("IBM");
  }

  public static boolean isIbm8() {
    return isIbm() && isJavaVersion(8);
  }

  public static boolean isGraalVM() {
    return runtime.vendorVersion.toLowerCase().contains("graalvm");
  }

  public static String getLangVersion() {
    return String.valueOf(javaVersion.major);
  }

  public static String getRuntimeVendor() {
    return runtime.vendor;
  }

  public static String getRuntimeVersion() {
    return runtime.version;
  }

  public static String getRuntimePatches() {
    return runtime.patches;
  }

  /**
   * Gets the JVM options.
   *
   * @return The JVM options, an empty collection if they can't be retrieved.
   */
  public static List<String> getVmOptions() {
    return JvmOptionsHolder.JVM_OPTIONS.VM_OPTIONS;
  }

  /**
   * Gets the command arguments.
   *
   * @return The command arguments, an empty collection if missing or can't be retrieved.
   */
  public static List<String> getCommandArguments() {
    return commandLine.arguments;
  }

  /**
   * Gets the JVM runtime main class name.
   *
   * @return The JVM runtime main class name, {@code null} if using JAR file instead or can't be
   *     retrieved.
   */
  public static @Nullable String getMainClass() {
    return commandLine.name != null && !isJarName(commandLine.name) ? commandLine.name : null;
  }

  /**
   * Gets the JVM runtime jar file.
   *
   * @return The JVM runtime jar file, {@code null} if using main class instead or can't be
   *     retrieved.
   */
  public static @Nullable String getJarFile() {
    return commandLine.name != null && isJarName(commandLine.name) ? commandLine.name : null;
  }

  private static boolean isJarName(String argument) {
    return argument.toLowerCase(Locale.ROOT).endsWith(".jar");
  }

  private static class JvmOptionsHolder {
    private static final JvmOptions JVM_OPTIONS = new JvmOptions();
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
          SystemProperties.get("java.version"),
          SystemProperties.get("java.runtime.version"),
          SystemProperties.get("java.runtime.name"),
          SystemProperties.get("java.vm.vendor"),
          SystemProperties.get("java.vendor.version"));
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
