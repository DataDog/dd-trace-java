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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TempLocationManagerTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "test1"})
  void testDefault(String subPath) throws Exception {
    TempLocationManager tempLocationManager = TempLocationManager.getInstance(true);
    Path tempDir = tempLocationManager.getTempDir(Paths.get(subPath));
    assertNotNull(tempDir);
    assertTrue(Files.exists(tempDir));
    assertTrue(Files.isDirectory(tempDir));
    assertTrue(Files.isWritable(tempDir));
    assertTrue(Files.isReadable(tempDir));
    assertTrue(Files.isExecutable(tempDir));
    assertTrue(tempDir.toString().contains("pid_" + PidHelper.getPid()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "test1"})
  void testFromConfig(String subPath) throws Exception {
    Path myDir =
        Files.createTempDirectory(
            "ddprof-test-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    myDir.toFile().deleteOnExit();
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    TempLocationManager tempLocationManager = new TempLocationManager(configProvider);
    Path tempDir = tempLocationManager.getTempDir(Paths.get(subPath));
    assertNotNull(tempDir);
    assertTrue(tempDir.toString().startsWith(myDir.toString()));
  }

  @Test
  void testFromConfigInvalid() {
    Path myDir = Paths.get(System.getProperty("java.io.tmpdir"), UUID.randomUUID().toString());
    // do not create the directory - it should trigger an exception
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    assertThrows(IllegalStateException.class, () -> new TempLocationManager(configProvider));
  }

  @Test
  void testFromConfigNotWritable() throws Exception {
    Path myDir =
        Files.createTempDirectory(
            "ddprof-test-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")));
    myDir.toFile().deleteOnExit();
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    TempLocationManager tempLocationManager = new TempLocationManager(configProvider);
    assertThrows(IllegalStateException.class, tempLocationManager::getTempDir);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "test1"})
  void testCleanup(String subPath) throws Exception {
    Path myDir =
        Files.createTempDirectory(
            "ddprof-test-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    myDir.toFile().deleteOnExit();
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, myDir.toString());
    props.put(
        ProfilingConfig.PROFILING_UPLOAD_PERIOD,
        "0"); // to force immediate cleanup; must be a string value!
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);
    TempLocationManager tempLocationManager = new TempLocationManager(configProvider);
    Path tempDir = tempLocationManager.getTempDir(Paths.get(subPath));
    assertNotNull(tempDir);

    // fake temp location
    Path fakeTempDir = tempDir.getParent();
    while (fakeTempDir != null && !fakeTempDir.endsWith("ddprof")) {
      fakeTempDir = fakeTempDir.getParent();
    }
    fakeTempDir = fakeTempDir.resolve("pid_0000");
    Files.createDirectories(fakeTempDir);
    Path tmpFile = Files.createFile(fakeTempDir.resolve("test.txt"));
    tmpFile.toFile().deleteOnExit(); // make sure this is deleted at exit
    fakeTempDir.toFile().deleteOnExit(); // also this one
    tempLocationManager.cleanup(false);
    // fake temp location should be deleted
    // real temp location should be kept
    assertFalse(Files.exists(fakeTempDir));
    assertTrue(Files.exists(tempDir));
  }
}
