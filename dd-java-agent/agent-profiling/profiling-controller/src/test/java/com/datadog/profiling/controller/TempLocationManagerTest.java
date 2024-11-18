package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.PidHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import org.junit.jupiter.api.Test;

public class TempLocationManagerTest {
  @Test
  void testDefault() throws Exception {
    TempLocationManager tempLocationManager = TempLocationManager.getInstance(true);
    Path tempDir = tempLocationManager.getTempDir();
    assertNotNull(tempDir);
    assertTrue(Files.exists(tempDir));
    assertTrue(Files.isDirectory(tempDir));
    assertTrue(Files.isWritable(tempDir));
    assertTrue(Files.isReadable(tempDir));
    assertTrue(Files.isExecutable(tempDir));
    assertTrue(tempDir.endsWith("pid_" + PidHelper.getPid()));
  }

  @Test
  void testFromConfig() throws Exception {
    Path myDir = Paths.get(System.getProperty("java.io.tmpdir"), "test1");
    Files.createDirectories(myDir);
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    TempLocationManager tempLocationManager = new TempLocationManager(configProvider);
    Path tempDir = tempLocationManager.getTempDir();
    assertNotNull(tempDir);
    assertTrue(tempDir.toString().startsWith(myDir.toString()));
  }

  @Test
  void testFromConfigInvalid() {
    Path myDir = Paths.get(System.getProperty("java.io.tmpdir"), "test2");
    // do not create the directory - it should trigger an exception
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertThrows(IllegalStateException.class, () -> new TempLocationManager(configProvider));
  }

  @Test
  void testFromConfigNotWritable() throws Exception {
    Path myDir = Paths.get(System.getProperty("java.io.tmpdir"), "test3");
    Files.createDirectories(
        myDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")));
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    TempLocationManager tempLocationManager = new TempLocationManager(configProvider);
    assertThrows(IllegalStateException.class, tempLocationManager::getTempDir);
  }

  @Test
  void testCleanup() throws Exception {
    Path myDir = Paths.get(System.getProperty("java.io.tmpdir"), "test4");
    Files.createDirectories(myDir);
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    TempLocationManager tempLocationManager = new TempLocationManager(configProvider);
    Path tempDir = tempLocationManager.getTempDir();
    assertNotNull(tempDir);

    // fake temp location
    Path fakeTempDir = tempDir.getParent().resolve("pid_00000");
    Files.createDirectories(fakeTempDir);
    tempLocationManager.cleanup(false);
    // fake temp location should be deleted
    // real temp location should be kept
    assertFalse(Files.exists(fakeTempDir));
    assertTrue(Files.exists(tempDir));
  }
}
