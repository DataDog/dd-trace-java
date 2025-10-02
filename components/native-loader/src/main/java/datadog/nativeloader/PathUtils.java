package datadog.nativeloader;

import static datadog.nativeloader.LibraryLoadException.UNSUPPORTED_ARCH;
import static datadog.nativeloader.LibraryLoadException.UNSUPPORTED_OS;

/** Utilities for generating library file paths to be requested from a {@link PathLocator} */
public final class PathUtils {
  private PathUtils() {}

  public static final String libPrefix(PlatformSpec platformSpec) {
    if (platformSpec.isMac() || platformSpec.isLinux()) {
      return "lib";
    } else if (platformSpec.isWindows()) {
      return "";
    } else {
      throw new IllegalArgumentException(UNSUPPORTED_OS);
    }
  }

  public static final String libFileName(PlatformSpec platformSpec, String libName) {
    return libPrefix(platformSpec) + libName + "." + dynamicLibExtension(platformSpec);
  }

  public static final String dynamicLibExtension(PlatformSpec platformSpec) {
    if (platformSpec.isLinux()) {
      return "so";
    } else if (platformSpec.isWindows()) {
      return "dll";
    } else if (platformSpec.isMac()) {
      return "dylib";
    } else {
      throw new IllegalArgumentException(UNSUPPORTED_OS);
    }
  }

  public static final String osPartOf(PlatformSpec platformSpec) {
    if (platformSpec.isLinux()) {
      return "linux";
    } else if (platformSpec.isWindows()) {
      return "win";
    } else if (platformSpec.isMac()) {
      return "macos";
    } else {
      throw new IllegalArgumentException(UNSUPPORTED_OS);
    }
  }

  public static final String archPartOf(PlatformSpec platformSpec) {
    if (platformSpec.isX86_64()) {
      return "x86_64";
    } else if (platformSpec.isAarch64()) {
      return "aarch64";
    } else if (platformSpec.isX86_32()) {
      return "x86_32";
    } else if (platformSpec.isArm32()) {
      return "arm32";
    } else {
      throw new IllegalArgumentException(UNSUPPORTED_ARCH);
    }
  }

  public static final String libcPartOf(PlatformSpec platformSpec) {
    if (!platformSpec.isLinux()) {
      return null;
    } else if (platformSpec.isMusl()) {
      return "musl";
    } else {
      return "libc";
    }
  }

  public static final String concatPath(String... pathParts) {
    StringBuilder builder = new StringBuilder();
    for (String pathPart : pathParts) {
      if (pathPart == null || pathPart.isEmpty()) continue;

      if (builder.length() != 0) builder.append('/');
      builder.append(pathPart);
    }
    return builder.toString();
  }
}
