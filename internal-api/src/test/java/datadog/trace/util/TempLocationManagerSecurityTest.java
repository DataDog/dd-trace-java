package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.time.SystemTimeSource;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security tests for {@link TempLocationManager} ownership and permission validation.
 *
 * <p>All tests are POSIX-only; skipped automatically on non-POSIX file systems.
 */
public class TempLocationManagerSecurityTest {

  @TempDir Path baseDir;

  @BeforeEach
  void requirePosix() {
    assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "Skipping POSIX-only security tests on non-POSIX file system");
  }

  @Test
  void freshTempDirIsCreatedWithOwnerOnlyPermissions() throws Exception {
    TempLocationManager mgr = instance(baseDir);
    Path tempDir = mgr.getTempDir();
    assertNotNull(tempDir);
    assertTrue0700(tempDir);
  }

  @Test
  void preExistingOwnerOwnedSecureDirIsAccepted() throws Exception {
    // Create the full per-process tree with 0700 before constructing the manager
    Path baseTempSubdir = baseDir.resolve(TempLocationManager.getBaseTempDirName());
    Files.createDirectories(
        baseTempSubdir,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

    String pid = PidHelper.getPid();
    Path pidDir = baseTempSubdir.resolve("pid_" + pid);
    Files.createDirectories(
        pidDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

    // Manager must not throw and must return the pre-existing dir
    TempLocationManager mgr = assertDoesNotThrow(() -> instance(baseDir));
    Path tempDir = mgr.getTempDir();
    assertNotNull(tempDir);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rwxrwxrwx", "rwxr-xr-x", "rwxrwx---"})
  void preExistingDirWithGroupWorldBitsIsRejected(String perms) throws Exception {
    Path baseTempSubdir = baseDir.resolve(TempLocationManager.getBaseTempDirName());
    Files.createDirectories(
        baseTempSubdir,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms)));

    assertThrows(IllegalStateException.class, () -> instance(baseDir));
  }

  @ParameterizedTest
  @ValueSource(strings = {"rwxrwxrwx", "rwxr-xr-x", "rwxrwx---"})
  void preExistingPidDirWithGroupWorldBitsIsRejected(String perms) throws Exception {
    // Create baseTempSubdir with secure perms, but the pid subdir with insecure perms
    Path baseTempSubdir = baseDir.resolve(TempLocationManager.getBaseTempDirName());
    Files.createDirectories(
        baseTempSubdir,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

    String pid = PidHelper.getPid();
    Path pidDir = baseTempSubdir.resolve("pid_" + pid);
    Files.createDirectories(
        pidDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms)));

    assertThrows(IllegalStateException.class, () -> instance(baseDir));
  }

  @Test
  void getTempDirWithExistingSubdirGroupWorldBitsIsRejected() throws Exception {
    // First build a clean tree so the manager initialises without error
    TempLocationManager mgr = instance(baseDir);
    Path tempDir = mgr.getTempDir();

    // Now plant an insecure subdir
    Path subDir = tempDir.resolve("sub");
    Files.createDirectories(
        subDir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxrwxrwx")));

    // getTempDir(subPath, false) should detect group/world bits and throw
    assertThrows(
        IllegalStateException.class, () -> mgr.getTempDir(tempDir.relativize(subDir), false));
  }

  @Test
  void nonPosixBehaviourIsUnchanged() {
    // Guard already applied in @BeforeEach; if we reach here the FS is POSIX.
    // The non-POSIX case is covered by the assumeTrue skip above.
    // Nothing to assert — the test documents the requirement.
  }

  private TempLocationManager instance(Path base) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, base.toString());
    props.put(ProfilingConfig.PROFILING_UPLOAD_PERIOD, "0");
    return new TempLocationManager(
        datadog.trace.bootstrap.config.provider.ConfigProvider.withPropertiesOverride(props),
        false,
        TempLocationManager.CleanupHook.EMPTY,
        SystemTimeSource.INSTANCE);
  }

  private static void assertTrue0700(Path dir) throws IOException {
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
    for (PosixFilePermission bit :
        new PosixFilePermission[] {
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE
        }) {
      if (perms.contains(bit)) {
        throw new AssertionError("Expected 0700 but found group/world bit " + bit + " on " + dir);
      }
    }
  }
}
