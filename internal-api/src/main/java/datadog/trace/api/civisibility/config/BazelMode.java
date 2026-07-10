package datadog.trace.api.civisibility.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import datadog.trace.api.Config;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.util.Strings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
  @Nullable private final String manifestPath;
  /* manifestDir is the directory containing the resolved manifest and the cached config files */
  @Nullable private final String manifestDir;
  /* payloadFilesEnabled reports whether Bazel payload-in-file mode is enabled */
  private final boolean payloadFilesEnabled;
  /* payloadsDir is the root directory containing payload output directories */
  @Nullable private final String payloadsDir;
  /* repoRoot is the absolute path to the runfiles workspace dir, used as a virtual repo root */
  @Nullable private final String repoRoot;

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

  @VisibleForTesting
  BazelMode(Config config) {
    String manifestRloc = config.getTestOptimizationManifestFile();
    if (Strings.isNotBlank(manifestRloc)) {
      LOGGER.debug("[bazel mode] Resolving manifest path from '{}'", manifestRloc);
      manifestPath = resolveRlocation(manifestRloc);
      if (manifestPath != null) {
        manifestDir = new File(manifestPath).getParent();
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

    // TEST_UNDECLARED_OUTPUTS_DIR is a Bazel-provided env var, not a DD configuration
    String undeclaredOutputsDir = ConfigHelper.env("TEST_UNDECLARED_OUTPUTS_DIR");
    if (config.isTestOptimizationPayloadsInFiles() && Strings.isNotBlank(undeclaredOutputsDir)) {
      payloadsDir = undeclaredOutputsDir + File.separator + "payloads";
      payloadFilesEnabled = true;
      LOGGER.info(
          "[bazel mode] Payload-in-files mode enabled with payload directory {}", payloadsDir);
    } else {
      payloadsDir = null;
      payloadFilesEnabled = false;
      if (config.isTestOptimizationPayloadsInFiles()) {
        LOGGER.warn(
            "[bazel mode] Payload-in-files mode requested but no payload directory was provided; disabling");
      }
    }

    repoRoot = resolveRepoRoot();
    if (repoRoot != null) {
      LOGGER.info("[bazel mode] Repo root resolved to runfiles workspace dir {}", repoRoot);
    }

    LOGGER.debug("[bazel mode] Resolved mode {}", this);
  }

  /**
   * Resolves the runfiles workspace dir to use as a virtual repo root for source-path resolution.
   * Returns {@code null} if the runfiles layout cannot be determined.
   */
  @Nullable
  private static String resolveRepoRoot() {
    String workspace = ConfigHelper.env("TEST_WORKSPACE");
    if (Strings.isBlank(workspace)) {
      return null;
    }

    String testSrcDir = ConfigHelper.env("TEST_SRCDIR");
    if (Strings.isNotBlank(testSrcDir)) {
      File candidate = new File(testSrcDir, workspace);
      if (candidate.isDirectory()) {
        return candidate.getAbsolutePath();
      }
    }

    String runfilesDir = ConfigHelper.env("RUNFILES_DIR");
    if (Strings.isNotBlank(runfilesDir)) {
      File candidate = new File(runfilesDir, workspace);
      if (candidate.isDirectory()) {
        return candidate.getAbsolutePath();
      }
    }

    return null;
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
        + ", repoRoot="
        + repoRoot
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
  public String getPayloadsDir() {
    return payloadsDir;
  }

  @Nullable
  public String getTestPayloadsDir() {
    return payloadsDir != null ? payloadsDir + File.separator + "tests" : null;
  }

  @Nullable
  public String getCoveragePayloadsDir() {
    return payloadsDir != null ? payloadsDir + File.separator + "coverage" : null;
  }

  @Nullable
  public String getTelemetryPayloadsDir() {
    return payloadsDir != null ? payloadsDir + File.separator + "telemetry" : null;
  }

  /**
   * Returns the absolute path of the runfiles workspace dir, suitable for use as a virtual repo
   * root by the source-path resolver. {@code null} when the runfiles layout cannot be determined.
   */
  @Nullable
  public String getRepoRoot() {
    return repoRoot;
  }

  @Nullable
  public String getSettingsPath() {
    return resolveToptFile(SETTINGS_FILE);
  }

  @Nullable
  public String getFlakyTestsPath() {
    return resolveToptFile(FLAKY_TESTS_FILE);
  }

  @Nullable
  public String getKnownTestsPath() {
    return resolveToptFile(KNOWN_TESTS_FILE);
  }

  @Nullable
  public String getTestManagementPath() {
    return resolveToptFile(TEST_MANAGEMENT_FILE);
  }

  @Nullable
  private String resolveToptFile(String relativePath) {
    if (manifestDir == null) {
      return null;
    }
    File file = new File(manifestDir, relativePath);
    return file.exists() ? file.getAbsolutePath() : null;
  }

  private static boolean isManifestCompatible(String manifestPath) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(new FileInputStream(manifestPath), UTF_8))) {
      String firstLine = reader.readLine();
      if (firstLine == null) {
        LOGGER.warn("[bazel mode] Manifest file is empty: {}", manifestPath);
        return false;
      }
      // manifest.txt first line has the shape `version=<int>`
      String trimmed = firstLine.trim();
      int separatorIdx = trimmed.indexOf('=');
      if (separatorIdx < 0 || !"version".equals(trimmed.substring(0, separatorIdx).trim())) {
        LOGGER.warn("[bazel mode] Could not parse manifest version from line: '{}'", trimmed);
        return false;
      }
      String versionValue = trimmed.substring(separatorIdx + 1).trim();
      try {
        int version = Integer.parseInt(versionValue);
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
   * Resolves a Bazel runfile rlocation to an absolute path string. Implements the 4-step algorithm:
   * direct path, $RUNFILES_DIR, $RUNFILES_MANIFEST_FILE, $TEST_SRCDIR.
   */
  @Nullable
  private static String resolveRlocation(String rlocation) {
    if (Strings.isBlank(rlocation)) {
      return null;
    }

    File direct = new File(rlocation);
    if (direct.exists()) {
      LOGGER.debug("[bazel mode] Resolved manifest directly");
      return direct.getAbsolutePath();
    }

    String runfilesDir = ConfigHelper.env("RUNFILES_DIR");
    if (Strings.isNotBlank(runfilesDir)) {
      File candidate = new File(runfilesDir, rlocation);
      if (candidate.exists()) {
        LOGGER.debug(
            "[bazel mode] Manifest resolved via RUNFILES_DIR (dir: {}, candidate: {})",
            runfilesDir,
            candidate);
        return candidate.getAbsolutePath();
      }
    }

    String manifestFile = ConfigHelper.env("RUNFILES_MANIFEST_FILE");
    if (Strings.isNotBlank(manifestFile)) {
      String resolved = lookupInRunfilesManifest(manifestFile, rlocation);
      if (resolved != null) {
        LOGGER.debug(
            "[bazel mode] Manifest resolved via RUNFILES_MANIFEST_FILE (candidate: {})", resolved);
        return resolved;
      }
    }

    String testSrcDir = ConfigHelper.env("TEST_SRCDIR");
    if (Strings.isNotBlank(testSrcDir)) {
      File candidate = new File(testSrcDir, rlocation);
      if (candidate.exists()) {
        LOGGER.debug(
            "[bazel mode] Manifest resolved via TEST_SRCDIR (dir: {}, candidate: {})",
            testSrcDir,
            candidate);
        return candidate.getAbsolutePath();
      }
    }

    return null;
  }

  @Nullable
  private static String lookupInRunfilesManifest(String manifestFile, String rlocation) {
    LOGGER.debug(
        "[bazel mode] Reading runfiles manifest {} for rlocation {}", manifestFile, rlocation);
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(new FileInputStream(manifestFile), UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        int spaceIdx = line.indexOf(' ');
        if (spaceIdx > 0 && line.substring(0, spaceIdx).equals(rlocation)) {
          return line.substring(spaceIdx + 1);
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

  @VisibleForTesting
  static void reset() {
    INSTANCE = null;
  }
}
