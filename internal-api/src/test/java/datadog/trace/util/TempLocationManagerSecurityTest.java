package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TempLocationManagerSecurityTest {
  @TempDir Path baseDir;

  @BeforeEach
  void requirePosix() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
  }

  @Test
  void createsFreshParentAndPidDirectoryWithOwnerOnlyPermissions() throws Exception {
    Path tempDir = instance().getTempDir();

    assertEquals("pid_" + PidHelper.getPid(), tempDir.getFileName().toString());
    Path parent = tempDir.getParent();
    assertEquals(baseDir, parent.getParent());
    assertTrue(
        parent.getFileName().toString().startsWith(TempLocationManager.getBaseTempDirName() + "_"));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(parent));
    assertEquals(
        PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(tempDir));
    assertEquals(Files.getOwner(baseDir), Files.getOwner(parent));
  }

  @Test
  void doesNotReuseParentLeftBehindByAnotherInstance() throws Exception {
    Path first = instance().getTempDir();
    Path marker = Files.createFile(first.resolve("keep.jfr"));
    Files.setPosixFilePermissions(first.getParent(), PosixFilePermissions.fromString("r-x------"));

    try {
      Path second = instance().getTempDir();

      assertNotEquals(first.getParent(), second.getParent());
      assertEquals(first.getFileName(), second.getFileName());
      assertTrue(Files.isWritable(second));
      assertTrue(Files.exists(marker));
    } finally {
      Files.setPosixFilePermissions(
          first.getParent(), PosixFilePermissions.fromString("rwx------"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"rwx------", "rwxrwxrwx", "rwxr-xr-x", "rwxrwx---"})
  void doesNotReuseLegacyDirectory(String permissions) throws Exception {
    Path legacy = baseDir.resolve(TempLocationManager.getBaseTempDirName());
    Path legacyPid =
        Files.createDirectories(
            legacy.resolve("pid_" + PidHelper.getPid()),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions)));
    Path marker = Files.createFile(legacyPid.resolve("keep.jfr"));

    Path tempDir = instance().getTempDir();

    assertNotEquals(legacy, tempDir.getParent());
    assertTrue(Files.exists(marker));
  }

  @Test
  void doesNotReuseExistingDirectoryWithMatchingPrefix() throws Exception {
    Path previous =
        Files.createDirectory(baseDir.resolve(TempLocationManager.getBaseTempDirName() + "_123"));

    Path tempDir = instance().getTempDir();

    assertNotEquals(previous, tempDir.getParent());
    assertTrue(Files.exists(previous));
  }

  @Test
  void cleanupLeavesDirectorySymlinksUntouched() throws Exception {
    Path outside = Files.createDirectory(baseDir.resolve("outside"));
    Path marker =
        Files.createFile(Files.createDirectory(outside.resolve("pid_fake")).resolve("keep.jfr"));
    Path link = baseDir.resolve(TempLocationManager.getBaseTempDirName() + "_123");
    Files.createSymbolicLink(link, outside);
    TempLocationManager manager = instance();

    assertTrue(manager.cleanup());

    assertTrue(Files.isSymbolicLink(link));
    assertTrue(Files.exists(marker));
  }

  @Test
  void cleanupSkipsExistingParentWithGroupOrWorldPermissions() throws Exception {
    Path previous =
        Files.createDirectories(
            baseDir.resolve(TempLocationManager.getBaseTempDirName() + "_123").resolve("pid_fake"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));
    Path marker = Files.createFile(previous.resolve("keep.jfr"));
    TempLocationManager manager = instance();

    assertTrue(manager.cleanup());

    assertTrue(Files.exists(marker));
  }

  @Test
  void cleanupRemovesStaleRandomParentAndPreservesRunningInstance() throws Exception {
    Path active = instance().getTempDir();
    Path previous =
        Files.createDirectories(
            baseDir.resolve(TempLocationManager.getBaseTempDirName() + "_123").resolve("pid_fake"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Files.createFile(previous.resolve("old.jfr"));
    TempLocationManager manager = instance();

    assertTrue(manager.cleanup());

    assertFalse(Files.exists(previous.getParent()));
    assertTrue(Files.isDirectory(active));
    assertTrue(Files.isDirectory(manager.getTempDir()));
  }

  @Test
  void cleanupPreservesNewEmptyParent() throws Exception {
    Path previous =
        Files.createDirectory(
            baseDir.resolve(TempLocationManager.getBaseTempDirName() + "_123"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Files.setLastModifiedTime(previous, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
    TempLocationManager manager = instance();

    assertTrue(manager.cleanup());

    assertTrue(Files.isDirectory(previous));
  }

  @Test
  void getTempDirRejectsPidDirectoryWithChangedPermissions() throws Exception {
    TempLocationManager manager = instance();
    Path tempDir = manager.getTempDir();
    Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxrwxrwx"));

    assertThrows(IllegalStateException.class, manager::getTempDir);
  }

  @Test
  void getTempDirRejectsDirectorySymlinks() throws Exception {
    TempLocationManager manager = instance();
    Path outside = Files.createDirectory(baseDir.resolve("outside"));
    Files.createSymbolicLink(manager.getTempDir().resolve("sub"), outside);

    assertThrows(IllegalStateException.class, () -> manager.getTempDir(Paths.get("sub"), false));
  }

  @Test
  void getTempDirWithExistingSubdirGroupWorldBitsIsRejected() throws Exception {
    TempLocationManager manager = instance();
    Path tempDir = manager.getTempDir();
    Path subDir =
        Files.createDirectory(
            tempDir.resolve("sub"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));

    assertThrows(
        IllegalStateException.class, () -> manager.getTempDir(tempDir.relativize(subDir), false));
  }

  private TempLocationManager instance() {
    Properties properties = new Properties();
    properties.setProperty(ProfilingConfig.PROFILING_TEMP_DIR, baseDir.toString());
    properties.setProperty(ProfilingConfig.PROFILING_UPLOAD_PERIOD, "0");
    return new TempLocationManager(
        ConfigProvider.withPropertiesOverride(properties),
        false,
        TempLocationManager.CleanupHook.EMPTY,
        SystemTimeSource.INSTANCE);
  }
}
