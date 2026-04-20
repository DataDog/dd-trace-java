package datadog.trace.api.civisibility.config;

import datadog.trace.api.Config;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.util.Strings;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BazelMode {

  private static final Logger LOGGER = LoggerFactory.getLogger(BazelMode.class);

  private static volatile BazelMode INSTANCE;

  private static final int SUPPORTED_MANIFEST_VERSION = 1;

  private static final String SETTINGS_FILE = "cache/http/settings.json";
  private static final String FLAKY_TESTS_FILE = "cache/http/flaky_tests.json";
  private static final String KNOWN_TESTS_FILE = "cache/http/known_tests.json";
  private static final String TEST_MANAGEMENT_FILE = "cache/http/test_management.json";

  /* manifestModeEnabled reports whether a supported manifest was found and can be used for config cache */
  private final boolean manifestModeEnabled;
  /* manifestPath is the resolved absolute path to the Bazel manifest while in manifest mode */
  @Nullable private final Path manifestPath;
  /* manifestDir is the directory containing the resolved manifest and the cached config files */
  @Nullable private final Path manifestDir;
  /* payloadFilesEnabled reports whether Bazel payload-in-file mode is enabled */
  private final boolean payloadFilesEnabled;
  /* payloadsDir is the root directory containing payload output directories */
  @Nullable private final Path payloadsDir;

  public static BazelMode get() {
    if (INSTANCE == null) {
      synchronized (BazelMode.class) {
        if (INSTANCE == null) {
          INSTANCE = new BazelMode(Config.get());
        }
      }
    }
    return INSTANCE;
  }

  // visible for testing
  BazelMode(Config config) {
    String manifestRloc = config.getTestOptimizationManifestFile();
    if (Strings.isNotBlank(manifestRloc)) {
      LOGGER.debug("[bazel mode] Resolving manifest path from '{}'", manifestRloc);
      manifestPath = resolveRlocation(manifestRloc);
      if (manifestPath != null) {
        manifestDir = manifestPath.getParent();
        manifestModeEnabled = isManifestCompatible(manifestPath);
        LOGGER.info(
            "[bazel mode] Manifest file resolved (path: '{}', enabled: {})",
            manifestPath,
            manifestModeEnabled);
      } else {
        manifestModeEnabled = false;
        manifestDir = null;
        LOGGER.warn(
            "[bazel mode] Could not resolve manifest file '{}', disabling manifest mode",
            manifestRloc);
      }
    } else {
      manifestModeEnabled = false;
      manifestPath = null;
      manifestDir = null;
    }

    payloadFilesEnabled = config.isTestOptimizationPayloadsInFiles();
    // TEST_UNDECLARED_OUTPUTS_DIR is a Bazel-provided env var, not a DD configuration
    String undeclaredOutputsDir = ConfigHelper.env("TEST_UNDECLARED_OUTPUTS_DIR");
    if (payloadFilesEnabled) {
      if (Strings.isNotBlank(undeclaredOutputsDir)) {
        Path resolved = null;
        try {
          resolved = Paths.get(undeclaredOutputsDir).resolve("payloads");
          LOGGER.info(
              "[bazel mode] Payload-in-files mode enabled with payload directory {}", resolved);
        } catch (InvalidPathException e) {
          LOGGER.warn(
              "[bazel mode] Payload-in-files mode enabled, but could not resolve payload directory");
        }
        payloadsDir = resolved;
      } else {
        LOGGER.warn(
            "[bazel mode] Payload-in-files mode enabled, but no payload directory was provided");
        payloadsDir = null;
      }
    } else {
      payloadsDir = null;
    }

    LOGGER.debug("[bazel mode] Resolved mode {}", this);
  }

  @Override
  public String toString() {
    return "BazelMode{"
        + "manifestModeEnabled="
        + manifestModeEnabled
        + ", manifestPath="
        + manifestPath
        + ", manifestDir="
        + manifestDir
        + ", payloadFilesEnabled="
        + payloadFilesEnabled
        + ", payloadsDir="
        + payloadsDir
        + '}';
  }

  /** Returns {@code true} if either manifest mode or payloads-in-files mode is active. */
  public boolean isEnabled() {
    return manifestModeEnabled || payloadFilesEnabled;
  }

  public boolean isManifestModeEnabled() {
    return manifestModeEnabled;
  }

  public boolean isPayloadFilesEnabled() {
    return payloadFilesEnabled;
  }

  @Nullable
  public Path getPayloadsDir() {
    return payloadsDir;
  }

  @Nullable
  public Path getTestPayloadsDir() {
    if (payloadsDir == null) {
      return null;
    }
    return payloadsDir.resolve("tests");
  }

  @Nullable
  public Path getCoveragePayloadsDir() {
    if (payloadsDir == null) {
      return null;
    }
    return payloadsDir.resolve("coverage");
  }

  @Nullable
  public Path getTelemetryPayloadsDir() {
    if (payloadsDir == null) {
      return null;
    }
    return payloadsDir.resolve("telemetry");
  }

  @Nullable
  public Path getSettingsPath() {
    return resolveToptFile(SETTINGS_FILE);
  }

  @Nullable
  public Path getFlakyTestsPath() {
    return resolveToptFile(FLAKY_TESTS_FILE);
  }

  @Nullable
  public Path getKnownTestsPath() {
    return resolveToptFile(KNOWN_TESTS_FILE);
  }

  @Nullable
  public Path getTestManagementPath() {
    return resolveToptFile(TEST_MANAGEMENT_FILE);
  }

  @Nullable
  private Path resolveToptFile(String relativePath) {
    if (manifestDir == null) {
      return null;
    }
    Path path = manifestDir.resolve(relativePath);
    return Files.exists(path) ? path : null;
  }

  private static boolean isManifestCompatible(Path manifestPath) {
    try (BufferedReader reader = Files.newBufferedReader(manifestPath)) {
      String firstLine = reader.readLine();
      if (firstLine == null) {
        LOGGER.warn("[bazel mode] Manifest file is empty: {}", manifestPath);
        return false;
      }
      // manifest.txt first line should contain the version number
      String trimmed = firstLine.trim();
      try {
        int version = Integer.parseInt(trimmed);
        if (version == SUPPORTED_MANIFEST_VERSION) {
          return true;
        }
        LOGGER.warn(
            "[bazel mode] Unsupported manifest version: {}, supported: {}",
            version,
            SUPPORTED_MANIFEST_VERSION);
        return false;
      } catch (NumberFormatException e) {
        LOGGER.warn("[bazel mode] Could not parse manifest version from line: '{}'", trimmed);
        return false;
      }
    } catch (IOException e) {
      LOGGER.warn("[bazel mode] Error reading manifest file: {}", manifestPath, e);
      return false;
    }
  }

  /**
   * Resolves a Bazel runfile rlocation path to an absolute path, checking whether it exists.
   * Implements the 4-step algorithm: check direct path, $RUNFILES_DIR, $RUNFILES_MANIFEST_FILE,
   * $TEST_SRCDIR.
   */
  @Nullable
  private static Path resolveRlocation(String rlocation) {
    if (Strings.isBlank(rlocation)) {
      return null;
    }

    try {
      Path directPath = Paths.get(rlocation);
      if (Files.exists(directPath)) {
        LOGGER.debug("[bazel mode] Resolved manifest directly");
        return directPath;
      }

      String runfilesDir = ConfigHelper.env("RUNFILES_DIR");
      if (Strings.isNotBlank(runfilesDir)) {
        Path candidate = Paths.get(runfilesDir, rlocation);
        if (Files.exists(candidate)) {
          LOGGER.debug(
              "[bazel mode] Manifest resolved via RUNFILES_DIR (dir: {}, candidate: {})",
              runfilesDir,
              candidate);
          return candidate;
        }
      }

      String manifestFile = ConfigHelper.env("RUNFILES_MANIFEST_FILE");
      if (Strings.isNotBlank(manifestFile)) {
        Path resolved = lookupInRunfilesManifest(Paths.get(manifestFile), rlocation);
        if (resolved != null) {
          LOGGER.debug(
              "[bazel mode] Manifest resolved via RUNFILES_MANIFEST_FILE (candidate: {})",
              resolved);
          return resolved;
        }
      }

      String testSrcDir = ConfigHelper.env("TEST_SRCDIR");
      if (Strings.isNotBlank(testSrcDir)) {
        Path candidate = Paths.get(testSrcDir, rlocation);
        if (Files.exists(candidate)) {
          LOGGER.debug(
              "[bazel mode] Manifest resolved via TEST_SRCDIR (dir: {}, candidate: {})",
              testSrcDir,
              candidate);
          return candidate;
        }
      }
    } catch (InvalidPathException ignored) {
    }

    return null;
  }

  @Nullable
  private static Path lookupInRunfilesManifest(Path manifestFile, String rlocation) {
    LOGGER.debug(
        "[bazel mode] Reading runfiles manifest {} for rlocation {}", manifestFile, rlocation);
    try (BufferedReader reader = Files.newBufferedReader(manifestFile)) {
      String line;
      while ((line = reader.readLine()) != null) {
        int spaceIdx = line.indexOf(' ');
        if (spaceIdx > 0 && line.substring(0, spaceIdx).equals(rlocation)) {
          return Paths.get(line.substring(spaceIdx + 1));
        }
      }
    } catch (IOException e) {
      LOGGER.debug("[bazel mode] Error reading runfiles manifest: {}", manifestFile, e);
      return null;
    }
    LOGGER.debug(
        "[bazel mode] Runfiles manifest {} did not contain rlocation {}", manifestFile, rlocation);
    return null;
  }

  // visible for testing
  static void reset() {
    INSTANCE = null;
  }
}
