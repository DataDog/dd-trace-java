package datadog.crashtracking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Security tests for crashtracking script initializer ownership and permission validation.
 *
 * <p>POSIX-only (skipped automatically on non-POSIX).
 */
public class ScriptInitializerSecurityTest {

  private Path tempDir;

  @BeforeEach
  void setup() throws IOException {
    requirePosix();
    tempDir = Files.createTempDirectory("dd-security-test-");
  }

  private static void requirePosix() {
    assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "Skipping POSIX-only security tests on non-POSIX file system");
  }

  @AfterEach
  void teardown() throws IOException {
    if (tempDir == null || !Files.exists(tempDir)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(tempDir)) {
      stream
          .sorted(Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(
              f -> {
                // Restore write permission before delete to handle read-only test artefacts
                f.setWritable(true, false);
                f.delete();
              });
    }
  }

  @Test
  void crashUploaderFreshDirIsOwnerRestricted() throws Exception {
    Path scriptFile = tempDir.resolve("dd_crash_uploader.sh");
    CrashUploaderScriptInitializer.initialize(scriptFile.toString(), "/tmp/hs_err.log");

    assertTrue(Files.exists(scriptFile), "Script should have been created");
    assertNoGroupWorldWriteBit(scriptFile);
    assertNoGroupWorldWriteBit(tempDir);
  }

  @Test
  void crashUploaderHijackedScriptIsRefused() throws Exception {
    Path scriptFile = tempDir.resolve("dd_crash_uploader.sh");
    // Plant an attacker-writable script
    Files.createFile(scriptFile);
    Files.setPosixFilePermissions(scriptFile, PosixFilePermissions.fromString("rwxrwxrwx"));

    long sizeBefore = Files.size(scriptFile);

    // Initializer must not proceed to write config (it returns false internally)
    assertDoesNotThrow(
        () -> CrashUploaderScriptInitializer.initialize(scriptFile.toString(), "/tmp/hs_err.log"));

    // The config file must NOT have been written (init refused)
    String cfgName = scriptFile.getFileName().toString().replace(".sh", "") + "_pid*.cfg";
    boolean configWritten =
        Files.list(tempDir).anyMatch(p -> p.getFileName().toString().endsWith(".cfg"));
    assertFalse(configWritten, "Config must not be written when the script is hijacked");

    // The script content must remain unchanged (empty file planted by attacker)
    long sizeAfter = Files.size(scriptFile);
    assertTrue(
        sizeAfter == sizeBefore,
        "Hijacked script must not be overwritten by the initializer (size changed)");
  }

  @Test
  void crashUploaderHijackedDirectoryIsRefused() throws Exception {
    // Create the script dir with world-writable perms to simulate hijack
    Path scriptDir = tempDir.resolve("hijacked_crash_dir");
    Files.createDirectories(scriptDir);
    Files.setPosixFilePermissions(scriptDir, PosixFilePermissions.fromString("rwxrwxrwx"));

    Path scriptFile = scriptDir.resolve("dd_crash_uploader.sh");
    assertDoesNotThrow(
        () -> CrashUploaderScriptInitializer.initialize(scriptFile.toString(), "/tmp/hs_err.log"));

    // Script must not have been written into the untrusted dir
    assertFalse(Files.exists(scriptFile), "Script must not be written into a hijacked directory");
  }

  @Test
  void crashUploaderRepairsPreviouslyGeneratedDirectory() throws Exception {
    // Simulate a directory left behind by a pre-upgrade version of the initializer that did not
    // lock down permissions: owned by us, but group/world readable+executable (0755) with no
    // write bits set for group/other.
    Path scriptDir = tempDir.resolve("legacy_crash_dir");
    Files.createDirectories(scriptDir);
    Files.setPosixFilePermissions(scriptDir, PosixFilePermissions.fromString("rwxr-xr-x"));

    Path scriptFile = scriptDir.resolve("dd_crash_uploader.sh");
    CrashUploaderScriptInitializer.initialize(scriptFile.toString(), "/tmp/hs_err.log");

    assertTrue(Files.exists(scriptFile), "Script should have been created in the repaired dir");
    assertPermissions(
        scriptDir,
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  @Test
  void oomeNotifierRepairsPreviouslyGeneratedDirectory() throws Exception {
    Path scriptDir = tempDir.resolve("legacy_oome_dir");
    Files.createDirectories(scriptDir);
    Files.setPosixFilePermissions(scriptDir, PosixFilePermissions.fromString("rwxr-xr-x"));

    Path scriptFile = scriptDir.resolve("dd_oome_notifier.sh");
    OOMENotifierScriptInitializer.initialize(scriptFile + " %p");

    assertTrue(Files.exists(scriptFile), "Script should have been created in the repaired dir");
    assertPermissions(
        scriptDir,
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  @Test
  void crashUploaderFreshDirHasExactlyOwnerPermissions() throws Exception {
    // Place the script under a child directory that does not exist yet, so the initializer
    // must go through its mkdirs()/permission-reset branch rather than the existing-directory
    // (already owner-only) branch that tempDir itself would take.
    Path scriptFile = tempDir.resolve("fresh-crash-dir").resolve("dd_crash_uploader.sh");
    CrashUploaderScriptInitializer.initialize(scriptFile.toString(), "/tmp/hs_err.log");

    // The directory is created by mkdirs() (subject to the process umask) before the
    // owner-only bits are applied, so any stray group/other bits left over from that
    // umask must have been cleared, not just overlaid with the owner bits.
    assertPermissions(
        scriptFile.getParent(),
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  @Test
  void oomeNotifierFreshDirHasExactlyOwnerPermissions() throws Exception {
    // Place the script under a child directory that does not exist yet, so the initializer
    // must go through its mkdirs()/permission-reset branch rather than the existing-directory
    // (already owner-only) branch that tempDir itself would take.
    Path scriptFile = tempDir.resolve("fresh-oome-dir").resolve("dd_oome_notifier.sh");
    OOMENotifierScriptInitializer.initialize(scriptFile + " %p");

    assertPermissions(
        scriptFile.getParent(),
        EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));
  }

  @Test
  void crashUploaderScriptFileHasNoGroupOrWorldReadBit() throws Exception {
    Path scriptFile = tempDir.resolve("dd_crash_uploader.sh");
    CrashUploaderScriptInitializer.initialize(scriptFile.toString(), "/tmp/hs_err.log");

    // The script is created with FileOutputStream, so its initial mode is subject to the
    // process umask (e.g. 0644 under a typical 0022 umask). The clear-then-set-owner-only
    // sequence must strip any inherited group/other bits, not just overlay owner bits on top
    // of them, otherwise a later JVM start rejects the script via isOwnedAndPrivate().
    assertPermissions(
        scriptFile, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
  }

  @Test
  void oomeNotifierScriptFileIsNotOwnerWritable() throws Exception {
    Path scriptFile = tempDir.resolve("dd_oome_notifier.sh");
    OOMENotifierScriptInitializer.initialize(scriptFile + " %p");

    // The write bit is deliberately not restored after the clear/set-owner-only sequence,
    // so the copied script must end up read+execute only, even for the owner.
    assertPermissions(
        scriptFile, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
  }

  @Test
  void oomeNotifierFreshDirIsOwnerRestricted() throws Exception {
    Path scriptFile = tempDir.resolve("dd_oome_notifier.sh");
    OOMENotifierScriptInitializer.initialize(scriptFile + " %p");

    assertTrue(Files.exists(scriptFile), "OOME notifier script should have been created");
    assertNoGroupWorldWriteBit(scriptFile);
    assertNoGroupWorldWriteBit(tempDir);
  }

  @Test
  void oomeNotifierHijackedScriptIsRefused() throws Exception {
    Path scriptFile = tempDir.resolve("dd_oome_notifier.sh");
    // Plant an attacker-writable script
    Files.createFile(scriptFile);
    Files.setPosixFilePermissions(scriptFile, PosixFilePermissions.fromString("rwxrwxrwx"));

    long sizeBefore = Files.size(scriptFile);

    assertDoesNotThrow(() -> OOMENotifierScriptInitializer.initialize(scriptFile + " %p"));

    // Config must not be written when initializer refuses
    boolean configWritten =
        Files.list(tempDir).anyMatch(p -> p.getFileName().toString().endsWith(".cfg"));
    assertFalse(configWritten, "Config must not be written when the OOME script is hijacked");

    // Script content must be unchanged
    long sizeAfter = Files.size(scriptFile);
    assertTrue(
        sizeAfter == sizeBefore, "Hijacked OOME script must not be overwritten by the initializer");
  }

  @Test
  void oomeNotifierHijackedDirectoryIsRefused() throws Exception {
    Path scriptDir = tempDir.resolve("hijacked_oome_dir");
    Files.createDirectories(scriptDir);
    Files.setPosixFilePermissions(scriptDir, PosixFilePermissions.fromString("rwxrwxrwx"));

    Path scriptFile = scriptDir.resolve("dd_oome_notifier.sh");
    assertDoesNotThrow(() -> OOMENotifierScriptInitializer.initialize(scriptFile + " %p"));

    assertFalse(Files.exists(scriptFile), "Script must not be written into a hijacked directory");
  }

  @Test
  void cleanPosixTreeEndToEndInitProducesScriptsAndConfigs() throws Exception {
    Path crashScript = tempDir.resolve("dd_crash_uploader.sh");
    Path oomeScript = tempDir.resolve("dd_oome_notifier.sh");

    // Initialise crash uploader
    assertDoesNotThrow(
        () -> CrashUploaderScriptInitializer.initialize(crashScript.toString(), "/tmp/hs_err.log"));

    // Initialise OOME notifier
    assertDoesNotThrow(() -> OOMENotifierScriptInitializer.initialize(oomeScript + " %p"));

    // Both scripts must exist and be non-empty
    assertTrue(Files.exists(crashScript), "dd_crash_uploader.sh must exist");
    assertTrue(Files.size(crashScript) > 0, "dd_crash_uploader.sh must be non-empty");
    assertTrue(Files.exists(oomeScript), "dd_oome_notifier.sh must exist");
    assertTrue(Files.size(oomeScript) > 0, "dd_oome_notifier.sh must be non-empty");

    // At least one .cfg file must have been written (crash uploader config)
    boolean crashCfgWritten =
        Files.list(tempDir).anyMatch(p -> p.getFileName().toString().endsWith(".cfg"));
    assertTrue(crashCfgWritten, "Crash uploader .cfg file must be written in the clean flow");
  }

  private static void assertPermissions(Path path, Set<PosixFilePermission> expected)
      throws IOException {
    Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path);
    assertEquals(
        actual,
        expected,
        "Expected permissions "
            + PosixFilePermissions.toString(expected)
            + " but found "
            + PosixFilePermissions.toString(actual)
            + " on "
            + path);
  }

  private static void assertNoGroupWorldWriteBit(Path path) throws IOException {
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
    for (PosixFilePermission bit :
        new PosixFilePermission[] {
          PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE
        }) {
      assertFalse(
          perms.contains(bit),
          "Expected no group/world write bit but found " + bit + " on " + path);
    }
  }
}
