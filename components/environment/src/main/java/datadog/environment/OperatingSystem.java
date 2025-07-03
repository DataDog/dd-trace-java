package datadog.environment;

import static java.util.Locale.ROOT;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Detects operating systems and libc library. */
public final class OperatingSystem {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCH_PROPERTY = "os.arch";

  private OperatingSystem() {}

  /**
   * Checks whether the operating system is Linux based.
   *
   * @return @{@code true} if operating system is Linux based, {@code false} otherwise.
   */
  public static boolean isLinux() {
    return propertyContains(OS_NAME_PROPERTY, "linux");
  }

  /**
   * Checks whether the operating system is Windows.
   *
   * @return @{@code true} if operating system is Windows, {@code false} otherwise.
   */
  public static boolean isWindows() {
    // https://mkyong.com/java/how-to-detect-os-in-java-systemgetpropertyosname/
    return propertyContains(OS_NAME_PROPERTY, "win");
  }

  /**
   * Checks whether the operating system is macOS.
   *
   * @return @{@code true} if operating system is macOS, {@code false} otherwise.
   */
  public static boolean isMacOs() {
    return propertyContains(OS_NAME_PROPERTY, "mac");
  }

  /**
   * Checks whether the architecture is AArch64.
   *
   * @return {@code true} if the architecture is AArch64, {@code false} otherwise.
   */
  public static boolean isAarch64() {
    return propertyContains(OS_ARCH_PROPERTY, "aarch64");
  }

  private static boolean propertyContains(String property, String content) {
    return SystemProperties.getOrDefault(property, "").toLowerCase(ROOT).contains(content);
  }

  /**
   * Checks whether the libc is MUSL.
   *
   * @return {@code true} if the libc is MUSL, {@code false} otherwise.
   */
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

  private static boolean isMuslProcSelfMaps() throws IOException {
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
   * `/ld-musl` for musl systems and probably something else for non-musl systems (e.g.
   * `/ld-linux-...`). However, if such string is missing should indicate that the system is not a
   * musl one.
   */
  private static boolean isMuslJavaExecutable() throws IOException {
    byte[] magic = new byte[] {(byte) 0x7f, (byte) 'E', (byte) 'L', (byte) 'F'};
    byte[] prefix = new byte[] {(byte) '/', (byte) 'l', (byte) 'd', (byte) '-'}; // '/ld-*'
    byte[] musl = new byte[] {(byte) 'm', (byte) 'u', (byte) 's', (byte) 'l'}; // 'musl'

    Path binary = Paths.get(SystemProperties.getOrDefault("java.home", ""), "bin", "java");
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
}
