package com.datadog.openfeature.internal.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** The version of this SDK, as written by the build. */
public final class SdkVersion {
  /** The SDK version, or {@code unknown} if the version file is missing. */
  public static final String VERSION = readVersion();

  private SdkVersion() {}

  private static String readVersion() {
    try (InputStream stream = SdkVersion.class.getResourceAsStream("/dd-openfeature.version")) {
      if (stream == null) {
        return "unknown";
      }
      final String line =
          new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).readLine();
      // Strip the git hash suffix.
      final int hash = line == null ? -1 : line.indexOf('~');
      return line == null ? "unknown" : hash < 0 ? line.trim() : line.substring(0, hash);
    } catch (final IOException e) {
      return "unknown";
    }
  }
}
