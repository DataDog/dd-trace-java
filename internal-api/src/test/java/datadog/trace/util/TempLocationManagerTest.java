package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    /*
     * This test simulates concurrent cleanup.
     * It utilizes a special hook to create synchronization points in the filetree walking routine,
     * allowing to delete the files at various points of execution.
     * The test makes sure that the cleanup is not interrupted and the file and directory being
     * deleted stays deleted.
     *
     * Synchronization uses CountDownLatch for deterministic coordination:
     * 1. Cleanup thread reaches hook point, signals via hookReached latch
     * 2. Main thread deletes file, signals via proceedSignal latch
     * 3. Cleanup thread continues and completes
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

    CountDownLatch hookReached = new CountDownLatch(1);
    CountDownLatch proceedSignal = new CountDownLatch(1);
    AtomicBoolean withTimeout = new AtomicBoolean(false);
    AtomicReference<Throwable> cleanupError = new AtomicReference<>();

    TempLocationManager.CleanupHook blocker =
        new TempLocationManager.CleanupHook() {
          private void syncPoint(boolean timeout) {
            withTimeout.compareAndSet(false, timeout);
            hookReached.countDown();
            try {
              if (!proceedSignal.await(30, TimeUnit.SECONDS)) {
                cleanupError.set(
                    new AssertionError("Cleanup thread: proceed signal timeout after 30s"));
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              cleanupError.set(e);
            }
          }

          @Override
          public FileVisitResult preVisitDirectory(
              Path dir, BasicFileAttributes attrs, boolean timeout) throws IOException {
            if (section.equals("preVisitDirectory") && dir.equals(fakeTempDir)) {
              syncPoint(timeout);
            }
            return null;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs, boolean timeout)
              throws IOException {
            if (section.equals("visitFile") && file.equals(fakeTempFile)) {
              syncPoint(timeout);
            }
            return null;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc, boolean timeout)
              throws IOException {
            if (section.equals("postVisitDirectory") && dir.equals(fakeTempDir)) {
              syncPoint(timeout);
            }
            return null;
          }
        };

    TempLocationManager mgr = instance(baseDir, true, blocker);

    // Wait for cleanup to reach the synchronization point
    boolean reached = hookReached.await(30, TimeUnit.SECONDS);
    assertTrue(reached, "Cleanup thread should reach hook within 30 seconds");

    // Now we know cleanup is paused at the hook - safe to delete
    Files.deleteIfExists(fakeTempFile);

    // Signal cleanup to proceed
    proceedSignal.countDown();

    // Wait for cleanup to complete
    assertTrue(mgr.waitForCleanup(30, TimeUnit.SECONDS), "Cleanup should complete within 30s");

    // Check for errors in cleanup thread
    Throwable error = cleanupError.get();
    if (error != null) {
      throw new AssertionError("Cleanup thread encountered error", error);
    }

    assertFalse(withTimeout.get(), "Cleanup should not have timed out");
    assertFalse(Files.exists(fakeTempFile));
    assertFalse(Files.exists(fakeTempDir));
  }

  @ParameterizedTest
  @MethodSource("timeoutTestArguments")
  void testCleanupWithTimeout(boolean shouldSucceed, String section) throws Exception {
    /*
     * Test that cleanup correctly handles timeout conditions.
     * Uses a ControllableTimeSource to deterministically advance time in the hook,
     * eliminating dependency on real wall-clock time and timer resolution.
     *
     * Timing strategy (fully deterministic):
     * - Simulated delay: 100ms per hook invocation
     * - Success case: 500ms timeout with 100ms delays → plenty of margin
     * - Failure case: 20ms timeout with 100ms delays → guaranteed timeout
     */
    long delayMs = 100;
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()));

    AtomicBoolean withTimeout = new AtomicBoolean(false);
    TempLocationManager.CleanupHook delayer =
        new TempLocationManager.CleanupHook() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir, BasicFileAttributes attrs, boolean timeout) throws IOException {
            withTimeout.compareAndSet(false, timeout);
            if (section.equals("preVisitDirectory")) {
              timeSource.advance(TimeUnit.MILLISECONDS.toNanos(delayMs));
            }
            return TempLocationManager.CleanupHook.super.preVisitDirectory(dir, attrs, timeout);
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc, boolean timeout)
              throws IOException {
            withTimeout.compareAndSet(false, timeout);
            if (section.equals("visitFileFailed")) {
              timeSource.advance(TimeUnit.MILLISECONDS.toNanos(delayMs));
            }
            return TempLocationManager.CleanupHook.super.visitFileFailed(file, exc, timeout);
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc, boolean timeout)
              throws IOException {
            withTimeout.compareAndSet(false, timeout);
            if (section.equals("postVisitDirectory")) {
              timeSource.advance(TimeUnit.MILLISECONDS.toNanos(delayMs));
            }
            return TempLocationManager.CleanupHook.super.postVisitDirectory(dir, exc, timeout);
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs, boolean timeout)
              throws IOException {
            withTimeout.compareAndSet(false, timeout);
            if (section.equals("visitFile")) {
              timeSource.advance(TimeUnit.MILLISECONDS.toNanos(delayMs));
            }
            return TempLocationManager.CleanupHook.super.visitFile(file, attrs, timeout);
          }
        };
    Path baseDir =
        Files.createTempDirectory(
            "ddprof-test-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    TempLocationManager instance = instance(baseDir, false, delayer, timeSource);
    Path mytempdir = instance.getTempDir();
    Path otherTempdir = mytempdir.getParent().resolve("pid_0000");
    Files.createDirectories(otherTempdir);
    Files.createFile(mytempdir.resolve("dummy"));
    Files.createFile(otherTempdir.resolve("dummy"));
    // Success: 500ms timeout (5 * 100ms) with 100ms delays → should complete
    // Failure: 20ms timeout (0.2 * 100ms) with 100ms delays → should timeout
    boolean rslt =
        instance.cleanup((long) (delayMs * (shouldSucceed ? 5 : 0.2d)), TimeUnit.MILLISECONDS);
    assertEquals(shouldSucceed, rslt);
    assertNotEquals(shouldSucceed, withTimeout.get()); // timeout = !shouldSucceed
  }

  private static Stream<Arguments> timeoutTestArguments() {
    List<Arguments> argumentsList = new ArrayList<>();
    for (String intercepted :
        new String[] {"preVisitDirectory", "visitFile", "postVisitDirectory"}) {
      argumentsList.add(Arguments.of(true, intercepted));
      argumentsList.add(Arguments.of(false, intercepted));
    }
    return argumentsList.stream();
  }

  private TempLocationManager instance(
      Path baseDir, boolean withStartupCleanup, TempLocationManager.CleanupHook cleanupHook) {
    return instance(baseDir, withStartupCleanup, cleanupHook, SystemTimeSource.INSTANCE);
  }

  private TempLocationManager instance(
      Path baseDir,
      boolean withStartupCleanup,
      TempLocationManager.CleanupHook cleanupHook,
      TimeSource timeSource) {
    Properties props = new Properties();
    props.put(ProfilingConfig.PROFILING_TEMP_DIR, baseDir.toString());
    props.put(
        ProfilingConfig.PROFILING_UPLOAD_PERIOD,
        "0"); // to force immediate cleanup; must be a string value!

    return new TempLocationManager(
        ConfigProvider.withPropertiesOverride(props), withStartupCleanup, cleanupHook, timeSource);
  }
}
