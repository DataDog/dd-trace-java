package datadog.crashtracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import java.io.File;
import java.io.IOException;
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
    when(config.getMergedCrashTrackingTags()).thenReturn(Collections.singletonMap("key", "value"));
    File tmpFile = File.createTempFile("ConfigManagerTest", null);
    tmpFile.deleteOnExit();
    ConfigManager.writeConfigToFile(config, tmpFile.toPath());
    ConfigManager.StoredConfig deserialized =
        ConfigManager.readConfig(Config.get(), tmpFile.toPath());
    Assertions.assertNotNull(deserialized);
    assertEquals("service", deserialized.service);
    assertEquals("version", deserialized.version);
    assertEquals("env", deserialized.env);
    assertEquals("key:value", deserialized.tags);
    assertEquals("1234", deserialized.runtimeId);
    assertEquals(
        Objects.requireNonNull(ProcessTags.getTagsForSerialization()).toString(),
        deserialized.processTags);
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
  }
}
