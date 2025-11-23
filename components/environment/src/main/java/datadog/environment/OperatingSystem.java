package datadog.environment;

import static datadog.environment.OperatingSystem.Type.LINUX;
import static datadog.environment.OperatingSystem.Type.MACOS;
import static datadog.environment.OperatingSystem.Type.WINDOWS;
import static java.util.Locale.ROOT;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Detects operating systems and libc library. */
public final class OperatingSystem {
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String OS_ARCH_PROPERTY = "os.arch";
  private static final Path TEMP_DIR = Paths.get(computeTempPath());
  private static final Type TYPE = Type.current();
  private static final Architecture ARCHITECTURE = Architecture.current();

  private OperatingSystem() {}

  /**
   * Checks whether the operating system is Linux based.
   *
   * @return @{@code true} if operating system is Linux based, {@code false} otherwise.
   */
  public static boolean isLinux() {
    return TYPE == LINUX;
  }

  /**
   * Checks whether the operating system is Windows.
   *
   * @return @{@code true} if operating system is Windows, {@code false} otherwise.
   */
  public static boolean isWindows() {
    return TYPE == WINDOWS;
  }

  /**
   * Checks whether the operating system is macOS.
   *
   * @return @{@code true} if operating system is macOS, {@code false} otherwise.
   */
  public static boolean isMacOs() {
    return TYPE == MACOS;
  }

  /**
   * Gets the operating system type.
   *
   * @return The operating system type, {@link Type#UNKNOWN} if not properly detected or supported.
   */
  public static Type type() {
    return TYPE;
  }

  /** Gets the operating system architecture . */
  public static Architecture architecture() {
    return ARCHITECTURE;
  }

  public static Path getTempDir() {
    return TEMP_DIR;
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

  private static String computeTempPath() {
    if (JavaVirtualMachine.isJ9()) {
      return computeJ9TempDir();
    }
    // See
    // https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-gettemppatha#remarks
    // and
    // the JDK OS-specific implementations of os::get_temp_directory(), i.e.
    // https://github.com/openjdk/jdk/blob/f50bd0d9ec65a6b9596805d0131aaefc1bb913f3/src/hotspot/os/bsd/os_bsd.cpp#L886-L904
    if (OperatingSystem.isLinux()) {
      return "/tmp";
    } else if (OperatingSystem.isWindows()) {
      return computeWindowsTempDir();
    } else if (OperatingSystem.isMacOs()) {
      return EnvironmentVariables.getOrDefault("TMPDIR", ".");
    } else {
      return SystemProperties.getOrDefault("java.io.tmpdir", ".");
    }
  }

  private static String computeWindowsTempDir() {
    return Stream.of("TMP", "TEMP", "USERPROFILE")
        .map(EnvironmentVariables::get)
        .filter(Objects::nonNull)
        .filter(((Predicate<String>) String::isEmpty).negate())
        .findFirst()
        .orElse("C:\\Windows");
  }

  @SuppressForbidden // Class.forName() as J9 specific
  private static String computeJ9TempDir() {
    try {
      https: // github.com/eclipse-openj9/openj9/blob/196082df056a990756a5571bfac29585fbbfbb42/jcl/src/java.base/share/classes/openj9/internal/tools/attach/target/IPC.java#L351
      return (String)
          Class.forName("openj9.internal.tools.attach.target.IPC")
              .getDeclaredMethod("getTmpDir")
              .invoke(null);
    } catch (Throwable t) {
      // Fall back to constants based on J9 source code, may not have perfect coverage
      String tmpDir = SystemProperties.get("java.io.tmpdir");
      if (tmpDir != null && !tmpDir.isEmpty()) {
        return tmpDir;
      } else if (OperatingSystem.isWindows()) {
        return "C:\\Documents";
      } else {
        return "/tmp";
      }
    }
  }

  public enum Type {
    WINDOWS("Windows"),
    MACOS("MacOS"),
    LINUX("Linux"),
    UNKNOWN("unknown");

    private final String name;

    Type(String name) {
      this.name = name;
    }

    static Type current() {
      String property = SystemProperties.getOrDefault(OS_NAME_PROPERTY, "").toLowerCase(ROOT);
      // https://mkyong.com/java/how-to-detect-os-in-java-systemgetpropertyosname/
      if (property.contains("linux")) {
        return LINUX;
      } else if (property.contains("win")) {
        return WINDOWS;
      } else if (property.contains("mac")) {
        return MACOS;
      } else {
        return UNKNOWN;
      }
    }

    @Override
    public String toString() {
      return this.name;
    }
  }

  /** Detects the operating system architecture. */
  public enum Architecture {
    X64("x86_64", "amd64", "k8"),
    X86("x86", "i386", "i486", "i586", "i686"),
    ARM("arm", "aarch32"),
    ARM64("arm64", "aarch64"),
    UNKNOWN();

    private final Set<String> identifiers;

    Architecture(String... identifiers) {
      this.identifiers = new HashSet<>(Arrays.asList(identifiers));
    }

    static Architecture of(String identifier) {
      for (Architecture architecture : Architecture.values()) {
        if (architecture.identifiers.contains(identifier)) {
          return architecture;
        }
      }
      return UNKNOWN;
    }

    static Architecture current() {
      String property = SystemProperties.getOrDefault(OS_ARCH_PROPERTY, "").toLowerCase(ROOT);
      return Architecture.of(property);
    }
  }
}
