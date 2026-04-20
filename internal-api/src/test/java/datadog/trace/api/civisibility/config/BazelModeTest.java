package datadog.trace.api.civisibility.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.environment.EnvironmentVariables;
import datadog.trace.api.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BazelModeTest {

  private EnvironmentVariables.EnvironmentVariablesProvider originalProvider;
  private TestEnvironmentVariables envProvider;

  @BeforeEach
  void setUp() {
    originalProvider = EnvironmentVariables.provider;
    envProvider = new TestEnvironmentVariables();
    EnvironmentVariables.provider = envProvider;
    BazelMode.reset();
  }

  @AfterEach
  void tearDown() {
    EnvironmentVariables.provider = originalProvider;
    BazelMode.reset();
  }

  @Test
  void disabledWhenNoConfigProvided() {
    BazelMode mode = new BazelMode(configWith(null, false));

    assertFalse(mode.isEnabled());
    assertFalse(mode.isManifestModeEnabled());
    assertFalse(mode.isPayloadFilesEnabled());
    assertNull(mode.getPayloadsDir());
    assertNull(mode.getTestPayloadsDir());
    assertNull(mode.getCoveragePayloadsDir());
    assertNull(mode.getTelemetryPayloadsDir());
    assertNull(mode.getSettingsPath());
  }

  @Test
  void manifestModeEnabledWithCompatibleManifest(@TempDir Path tmp) throws IOException {
    Path manifest = writeManifest(tmp, "1");

    BazelMode mode = new BazelMode(configWith(manifest.toString(), false));

    assertTrue(mode.isEnabled());
    assertTrue(mode.isManifestModeEnabled());
  }

  @Test
  void manifestModeDisabledForUnsupportedVersion(@TempDir Path tmp) throws IOException {
    Path manifest = writeManifest(tmp, "999");

    BazelMode mode = new BazelMode(configWith(manifest.toString(), false));

    assertFalse(mode.isManifestModeEnabled());
  }

  @Test
  void manifestModeDisabledForUnparseableVersion(@TempDir Path tmp) throws IOException {
    Path manifest = writeManifest(tmp, "not-a-number");

    BazelMode mode = new BazelMode(configWith(manifest.toString(), false));

    assertFalse(mode.isManifestModeEnabled());
  }

  @Test
  void manifestResolvedViaRunfilesDir(@TempDir Path tmp) throws IOException {
    Path runfiles = Files.createDirectories(tmp.resolve("runfiles"));
    Path manifest = writeManifest(runfiles.resolve(".testoptimization"), "1");
    envProvider.set("RUNFILES_DIR", runfiles.toString());
    String rlocation = runfiles.relativize(manifest).toString();

    BazelMode mode = new BazelMode(configWith(rlocation, false));

    assertTrue(mode.isManifestModeEnabled());
  }

  @Test
  void manifestResolvedViaRunfilesManifestFile(@TempDir Path tmp) throws IOException {
    Path actualManifest = writeManifest(tmp.resolve(".testoptimization"), "1");
    Path runfilesManifest = tmp.resolve("runfiles.manifest");
    Files.write(
        runfilesManifest,
        ("myproj/.testoptimization/manifest.txt " + actualManifest + "\n")
            .getBytes(StandardCharsets.UTF_8));
    envProvider.set("RUNFILES_MANIFEST_FILE", runfilesManifest.toString());

    BazelMode mode = new BazelMode(configWith("myproj/.testoptimization/manifest.txt", false));

    assertTrue(mode.isManifestModeEnabled());
  }

  @Test
  void cachePathsResolveOnlyWhenFileExists(@TempDir Path tmp) throws IOException {
    Path manifestDir = Files.createDirectories(tmp.resolve(".testoptimization"));
    Path manifest = writeManifest(manifestDir, "1");
    Path httpDir = Files.createDirectories(manifestDir.resolve("cache/http"));
    Files.write(httpDir.resolve("settings.json"), "{}".getBytes(StandardCharsets.UTF_8));

    BazelMode mode = new BazelMode(configWith(manifest.toString(), false));

    assertNotNull(mode.getSettingsPath());
    assertEquals(httpDir.resolve("settings.json"), mode.getSettingsPath());
    // files that do not exist return null
    assertNull(mode.getKnownTestsPath());
    assertNull(mode.getTestManagementPath());
    assertNull(mode.getFlakyTestsPath());
  }

  @Test
  void payloadDirsDerivedFromUndeclaredOutputsDir(@TempDir Path tmp) {
    envProvider.set("TEST_UNDECLARED_OUTPUTS_DIR", tmp.toString());

    BazelMode mode = new BazelMode(configWith(null, true));

    assertTrue(mode.isEnabled());
    assertTrue(mode.isPayloadFilesEnabled());
    assertEquals(tmp.resolve("payloads"), mode.getPayloadsDir());
    assertEquals(tmp.resolve("payloads/tests"), mode.getTestPayloadsDir());
    assertEquals(tmp.resolve("payloads/coverage"), mode.getCoveragePayloadsDir());
    assertEquals(tmp.resolve("payloads/telemetry"), mode.getTelemetryPayloadsDir());
  }

  @Test
  void payloadDirsNullWhenUndeclaredOutputsDirMissing() {
    BazelMode mode = new BazelMode(configWith(null, true));

    assertTrue(mode.isPayloadFilesEnabled());
    assertNull(mode.getPayloadsDir());
    assertNull(mode.getTestPayloadsDir());
    assertNull(mode.getCoveragePayloadsDir());
    assertNull(mode.getTelemetryPayloadsDir());
  }

  private static Config configWith(String manifestRloc, boolean payloadsInFiles) {
    Config config = mock(Config.class);
    when(config.getTestOptimizationManifestFile()).thenReturn(manifestRloc);
    when(config.isTestOptimizationPayloadsInFiles()).thenReturn(payloadsInFiles);
    return config;
  }

  private static Path writeManifest(Path dir, String content) throws IOException {
    Files.createDirectories(dir);
    Path manifest = dir.resolve("manifest.txt");
    Files.write(manifest, content.getBytes(StandardCharsets.UTF_8));
    return manifest;
  }

  static class TestEnvironmentVariables extends EnvironmentVariables.EnvironmentVariablesProvider {
    private final Map<String, String> env = new HashMap<>();

    void set(String name, String value) {
      env.put(name, value);
    }

    @Override
    public String get(String name) {
      return env.get(name);
    }

    @Override
    public Map<String, String> getAll() {
      return env;
    }
  }
}
