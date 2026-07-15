package datadog.crashtracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import datadog.trace.util.PidHelper;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ConfigManagerTest {
  @Test
  public void testConfigWriteAndRead() throws IOException {
    Config config = mock(Config.class);
    when(config.getWellKnownTags())
        .thenReturn(new WellKnownTags("1234", "", "env", "service", "version", ""));
    when(config.isCrashTrackingAgentless()).thenReturn(false);
    when(config.isCrashTrackingErrorsIntakeEnabled()).thenReturn(true);
    when(config.isCrashTrackingExtendedInfoEnabled()).thenReturn(true);
    when(config.getMergedCrashTrackingTags()).thenReturn(Collections.singletonMap("key", "value"));
    File tmpFile = File.createTempFile("ConfigManagerTest", null);
    tmpFile.deleteOnExit();
    ConfigManager.writeConfigToFile(config, tmpFile);
    ConfigManager.StoredConfig deserialized = ConfigManager.readConfig(Config.get(), tmpFile);
    Assertions.assertNotNull(deserialized);
    assertEquals("service", deserialized.service);
    assertEquals("version", deserialized.version);
    assertEquals("env", deserialized.env);
    assertEquals("key:value", deserialized.tags);
    assertEquals("1234", deserialized.runtimeId);
    assertEquals(
        Objects.requireNonNull(ProcessTags.getTagsForSerialization()).toString(),
        deserialized.processTags);
    assertFalse(deserialized.agentless);
    assertTrue(deserialized.sendToErrorTracking);
    assertTrue(deserialized.extendedInfoEnabled);
  }

  @Test
  public void testUpdateCrashConfigEntryPreservesUnknownKeysAndPatchesTarget() throws IOException {
    File tmpDir = Files.createTempDirectory("ConfigManagerTest").toFile();
    tmpDir.deleteOnExit();
    File scriptFile = new File(tmpDir, "dd_crash_uploader.sh");
    File cfgFile = new File(tmpDir, "dd_crash_uploader_pid" + PidHelper.getPid() + ".cfg");
    cfgFile.deleteOnExit();

    ConfigManager.writeConfigToPath(
        scriptFile, "agent", "/path/to/agent.jar", "hs_err", "/tmp/hs_err.log");

    ConfigManager.updateCrashConfigEntry("waf_rules_version", "1.2.3");

    ConfigManager.StoredConfig deserialized = ConfigManager.readConfig(Config.get(), cfgFile);
    Assertions.assertNotNull(deserialized);
    assertEquals("1.2.3", deserialized.wafRulesVersion);

    String content = new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8);
    assertTrue(content.contains("agent=/path/to/agent.jar"));
    assertTrue(content.contains("hs_err=/tmp/hs_err.log"));

    ConfigManager.updateCrashConfigEntry("waf_rules_version", "4.5.6");
    ConfigManager.StoredConfig updated = ConfigManager.readConfig(Config.get(), cfgFile);
    Assertions.assertNotNull(updated);
    assertEquals("4.5.6", updated.wafRulesVersion);
    String updatedContent =
        new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8);
    assertTrue(updatedContent.contains("agent=/path/to/agent.jar"));
  }

  @Test
  public void testStoredConfigDefaults() {
    Config config = mock(Config.class);
    when(config.getServiceName()).thenReturn("service");
    when(config.getVersion()).thenReturn("version");
    when(config.getEnv()).thenReturn("env");
    ConfigManager.StoredConfig storedConfig =
        new ConfigManager.StoredConfig.Builder(config).build();
    assertEquals("service", storedConfig.service);
    assertEquals("version", storedConfig.version);
    assertEquals("env", storedConfig.env);
    assertFalse(storedConfig.agentless);
    assertFalse(storedConfig.sendToErrorTracking);
    assertFalse(storedConfig.extendedInfoEnabled);
  }
}
