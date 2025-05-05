package datadog.trace.api;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

  private static final Version JAVA_VERSION = parseJavaVersion(System.getProperty("java.version"));
  private static final JvmRuntime RUNTIME = new JvmRuntime();

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

  /* The method splits java version string by digits. Delimiters are: dot, underscore and plus */
  private static List<Integer> splitDigits(String str) {
    List<Integer> results = new ArrayList<>();

    int len = str.length();

    int value = 0;
    for (int i = 0; i < len; i++) {
      char ch = str.charAt(i);
      if (ch >= '0' && ch <= '9') {
        value = value * 10 + (ch - '0');
      } else if (ch == '.' || ch == '_' || ch == '+') {
        results.add(value);
        value = 0;
      } else {
        throw new NumberFormatException();
      }
    }
    results.add(value);
    return results;
  }

  private static Version parseJavaVersion(String javaVersion) {
    // Remove pre-release part, usually -ea
    final int indexOfDash = javaVersion.indexOf('-');
    if (indexOfDash >= 0) {
      javaVersion = javaVersion.substring(0, indexOfDash);
    }

    int major = 0;
    int minor = 0;
    int update = 0;

    try {
      List<Integer> nums = splitDigits(javaVersion);
      major = nums.get(0);

      // for java 1.6/1.7/1.8
      if (major == 1) {
        major = nums.get(1);
        minor = nums.get(2);
        update = nums.get(3);
      } else {
        minor = nums.get(1);
        update = nums.get(2);
      }
    } catch (NumberFormatException | IndexOutOfBoundsException e) {
      // unable to parse version string - do nothing
    }
    return new Version(major, minor, update);
  }

  static final class Version {
    public final int major, minor, update;

    public Version(int major, int minor, int update) {
      this.major = major;
      this.minor = minor;
      this.update = update;
    }

    public boolean is(int major) {
      return this.major == major;
    }

    public boolean is(int major, int minor) {
      return this.major == major && this.minor == minor;
    }

    public boolean is(int major, int minor, int update) {
      return this.major == major && this.minor == minor && this.update == update;
    }

    public boolean isAtLeast(int major, int minor, int update) {
      return isAtLeast(this.major, this.minor, this.update, major, minor, update);
    }

    public boolean isBetween(
        int fromMajor, int fromMinor, int fromUpdate, int toMajor, int toMinor, int toUpdate) {
      return isAtLeast(toMajor, toMinor, toUpdate, fromMajor, fromMinor, fromUpdate)
          && isAtLeast(fromMajor, fromMinor, fromUpdate)
          && !isAtLeast(toMajor, toMinor, toUpdate);
    }

    private static boolean isAtLeast(
        int major, int minor, int update, int atLeastMajor, int atLeastMinor, int atLeastUpdate) {
      return (major > atLeastMajor)
          || (major == atLeastMajor && minor > atLeastMinor)
          || (major == atLeastMajor && minor == atLeastMinor && update >= atLeastUpdate);
    }
  }

  static final class JvmRuntime {
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

    public JvmRuntime() {
      this(
          System.getProperty("java.version"),
          System.getProperty("java.runtime.version"),
          System.getProperty("java.runtime.name"),
          System.getProperty("java.vm.vendor"),
          System.getProperty("java.vendor.version"));
    }

    // Only visible for testing
    JvmRuntime(String javaVer, String rtVer, String name, String vendor, String vendorVersion) {
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
   * Check if the Java version is between {@code fromMajor} (inclusive) and {@code toMajor}
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
   * Check if the Java version is between {@code fromMajor.fromMinor} (inclusive) and {@code
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
   * Check if the Java version is between {@code fromMajor.fromMinor.fromUpdate} (inclusive) and
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

  public static boolean isLinux() {
    return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
  }

  public static boolean isWindows() {
    // https://mkyong.com/java/how-to-detect-os-in-java-systemgetpropertyosname/
    final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    return os.contains("win");
  }

  public static boolean isMac() {
    final String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    return os.contains("mac");
  }

  public static boolean isAarch64() {
    return System.getProperty("os.arch").toLowerCase().contains("aarch64");
  }

  public static boolean isMusl() {
    if (!isLinux()) {
      return false;
    }
    // check the Java exe then fall back to proc/self maps
    try {
      return isMuslJavaExecutable();
    } catch (IOException e) {
      try {
        return isMuslProcSelfMaps();
      } catch (IOException ignore) {
        return false;
      }
    }
  }

  static boolean isMuslProcSelfMaps() throws IOException {
    try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.contains("-musl-")) {
          return true;
        }
        if (line.contains("/libc.")) {
          return false;
        }
      }
    }
    return false;
  }

  /**
   * There is information about the linking in the ELF file. Since properly parsing ELF is not
   * trivial this code will attempt a brute-force approach and will scan the first 4096 bytes of the
   * 'java' program image for anything prefixed with `/ld-` - in practice this will contain
   * `/ld-musl` for musl systems and probably something else for non-musl systems (eg.
   * `/ld-linux-...`). However, if such string is missing should indicate that the system is not a
   * musl one.
   */
  static boolean isMuslJavaExecutable() throws IOException {

    byte[] magic = new byte[] {(byte) 0x7f, (byte) 'E', (byte) 'L', (byte) 'F'};
    byte[] prefix = new byte[] {(byte) '/', (byte) 'l', (byte) 'd', (byte) '-'}; // '/ld-*'
    byte[] musl = new byte[] {(byte) 'm', (byte) 'u', (byte) 's', (byte) 'l'}; // 'musl'

    Path binary = Paths.get(System.getProperty("java.home"), "bin", "java");
    byte[] buffer = new byte[4096];

    try (InputStream is = Files.newInputStream(binary)) {
      int read = is.read(buffer, 0, 4);
      if (read != 4 || !containsArray(buffer, 0, magic)) {
        throw new IOException(Arrays.toString(buffer));
      }
      read = is.read(buffer);
      if (read <= 0) {
        throw new IOException();
      }
      int prefixPos = 0;
      for (int i = 0; i < read; i++) {
        if (buffer[i] == prefix[prefixPos]) {
          if (++prefixPos == prefix.length) {
            return containsArray(buffer, i + 1, musl);
          }
        } else {
          prefixPos = 0;
        }
      }
    }
    return false;
  }

  private static boolean containsArray(byte[] container, int offset, byte[] contained) {
    for (int i = 0; i < contained.length; i++) {
      int leftPos = offset + i;
      if (leftPos >= container.length) {
        return false;
      }
      if (container[leftPos] != contained[i]) {
        return false;
      }
    }
    return true;
  }

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
}
