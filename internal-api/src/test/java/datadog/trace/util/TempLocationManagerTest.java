package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.test.util.Flaky;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    assertThrows(IllegalStateException.class, () -> new TempLocationManager(configProvider));
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
    while (fakeTempDir != null && !fakeTempDir.getFileName().toString().contains("ddprof")) {
      fakeTempDir = fakeTempDir.getParent();
    }
    fakeTempDir = fakeTempDir.resolve("pid_0000");
    Files.createDirectories(fakeTempDir);
    Path tmpFile = Files.createFile(fakeTempDir.resolve("test.txt"));
    tmpFile.toFile().deleteOnExit(); // make sure this is deleted at exit
    fakeTempDir.toFile().deleteOnExit(); // also this one
    boolean rslt = tempLocationManager.cleanup();
    // fake temp location should be deleted
    // real temp location should be kept
    assertFalse(rslt && Files.exists(fakeTempDir));
    assertTrue(Files.exists(tempDir));
  }

  @ParameterizedTest
  @ValueSource(strings = {"preVisitDirectory", "visitFile", "postVisitDirectory"})
  void testConcurrentCleanup(String section) throws Exception {
    /* This test simulates concurrent cleanup
      It utilizes a special hook to create synchronization points in the filetree walking routine,
      allowing to delete the files at various points of execution.
      The test makes sure that the cleanup is not interrupted and the file and directory being deleted
      stays deleted.
    */
    Path baseDir =
        Files.createTempDirectory(
            "ddprof-test-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));

    Path fakeTempDir =
        baseDir.resolve(TempLocationManager.getBaseTempDirName() + "/pid_1234/scratch");
    Files.createDirectories(fakeTempDir);
    Path fakeTempFile = fakeTempDir.resolve("libxxx.so");
    Files.createFile(fakeTempFile);

    fakeTempDir.toFile().deleteOnExit();
    fakeTempFile.toFile().deleteOnExit();

    Phaser phaser = new Phaser(2);

    TempLocationManager.CleanupHook blocker =
        new TempLocationManager.CleanupHook() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (section.equals("preVisitDirectory") && dir.equals(fakeTempDir)) {
              phaser.arriveAndAwaitAdvance();
              phaser.arriveAndAwaitAdvance();
            }
            return null;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (section.equals("visitFile") && file.equals(fakeTempFile)) {
              phaser.arriveAndAwaitAdvance();
              phaser.arriveAndAwaitAdvance();
            }
            return null;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (section.equals("postVisitDirectory") && dir.equals(fakeTempDir)) {
              phaser.arriveAndAwaitAdvance();
              phaser.arriveAndAwaitAdvance();
            }
            return null;
          }
        };

    TempLocationManager mgr = instance(baseDir, true, blocker);

    // wait for the cleanup start
    phaser.arriveAndAwaitAdvance();
    Files.deleteIfExists(fakeTempFile);
    phaser.arriveAndAwaitAdvance();
    mgr.waitForCleanup(30, TimeUnit.SECONDS);

    assertFalse(Files.exists(fakeTempFile));
    assertFalse(Files.exists(fakeTempDir));
  }

  @Flaky("https://datadoghq.atlassian.net/browse/PROF-11290")
  @ParameterizedTest
  @MethodSource("timeoutTestArguments")
  void testCleanupWithTimeout(boolean selfCleanup, boolean shouldSucceed, String section)
      throws Exception {
    long timeoutMs = 10;
    TempLocationManager.CleanupHook delayer =
        new TempLocationManager.CleanupHook() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (section.equals("preVisitDirectory")) {
              LockSupport.parkNanos(timeoutMs * 1_000_000);
            }
            return TempLocationManager.CleanupHook.super.preVisitDirectory(dir, attrs);
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            if (section.equals("visitFileFailed")) {
              LockSupport.parkNanos(timeoutMs * 1_000_000);
            }
            return TempLocationManager.CleanupHook.super.visitFileFailed(file, exc);
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (section.equals("postVisitDirectory")) {
              LockSupport.parkNanos(timeoutMs * 1_000_000);
            }
            return TempLocationManager.CleanupHook.super.postVisitDirectory(dir, exc);
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (section.equals("visitFile")) {
              LockSupport.parkNanos(timeoutMs * 1_000_000);
            }
            return TempLocationManager.CleanupHook.super.visitFile(file, attrs);
          }
        };
    Path baseDir =
        Files.createTempDirectory(
            "ddprof-test-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    TempLocationManager instance = instance(baseDir, false, delayer);
    Path mytempdir = instance.getTempDir();
    Path otherTempdir = mytempdir.getParent().resolve("pid_0000");
    Files.createDirectories(otherTempdir);
    Files.createFile(mytempdir.resolve("dummy"));
    Files.createFile(otherTempdir.resolve("dummy"));
    boolean rslt =
        instance.cleanup((long) (timeoutMs * (shouldSucceed ? 20 : 0.5d)), TimeUnit.MILLISECONDS);
    assertEquals(shouldSucceed, rslt);
  }

  private static Stream<Arguments> timeoutTestArguments() {
    List<Arguments> argumentsList = new ArrayList<>();
    for (boolean selfCleanup : new boolean[] {true, false}) {
      for (String intercepted :
          new String[] {"preVisitDirectory", "visitFile", "postVisitDirectory"}) {
        argumentsList.add(Arguments.of(selfCleanup, true, intercepted));
        argumentsList.add(Arguments.of(selfCleanup, false, intercepted));
      }
    }
    return argumentsList.stream();
  }

  private TempLocationManager instance(
      Path baseDir, boolean withStartupCleanup, TempLocationManager.CleanupHook cleanupHook)
      throws IOException {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, baseDir.toString());
    props.put(
        ProfilingConfig.PROFILING_UPLOAD_PERIOD,
        "0"); // to force immediate cleanup; must be a string value!

    return new TempLocationManager(
        ConfigProvider.withPropertiesOverride(props), withStartupCleanup, cleanupHook);
  }
}
