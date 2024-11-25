package com.datadog.profiling.controller;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.PidHelper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A manager class for temporary locations used by the profiling product. The temporary location is
 * keyed by the process ID and allows for cleanup of orphaned temporary files on startup by querying
 * the list of running Java processes and cleaning up any temporary locations that do not correspond
 * to a running Java process. Also, the temporary location is cleaned up on shutdown.
 */
public final class TempLocationManager {
  private static final Logger log = LoggerFactory.getLogger(TempLocationManager.class);

  private static final class SingletonHolder {
    private static final TempLocationManager INSTANCE = new TempLocationManager();
  }

  interface CleanupHook extends FileVisitor<Path> {
    CleanupHook EMPTY = new CleanupHook() {};

    @Override
    default FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
      return null;
    }

    @Override
    default FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      return null;
    }

    @Override
    default FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      return null;
    }

    @Override
    default FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      return null;
    }

    default void onCleanupStart(boolean selfCleanup, long timeout, TimeUnit unit) {}
  }

  private class CleanupVisitor implements FileVisitor<Path> {
    private boolean shouldClean;

    private final Set<String> pidSet = PidHelper.getJavaPids();

    private final boolean cleanSelf;
    private final Instant cutoff;
    private final Instant timeoutTarget;

    private boolean terminated = false;

    CleanupVisitor(boolean cleanSelf, long timeout, TimeUnit unit) {
      this.cleanSelf = cleanSelf;
      this.cutoff = Instant.now().minus(cutoffSeconds, ChronoUnit.SECONDS);
      this.timeoutTarget =
          timeout > -1
              ? Instant.now().plus(TimeUnit.MILLISECONDS.convert(timeout, unit), ChronoUnit.MILLIS)
              : null;
    }

    boolean isTerminated() {
      return terminated;
    }

    private boolean isTimedOut() {
      return timeoutTarget != null && Instant.now().isAfter(timeoutTarget);
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
      if (isTimedOut()) {
        log.debug("Cleaning task timed out");
        terminated = true;
        return FileVisitResult.TERMINATE;
      }
      cleanupTestHook.preVisitDirectory(dir, attrs);

      if (dir.equals(baseTempDir)) {
        return FileVisitResult.CONTINUE;
      }
      String fileName = dir.getFileName().toString();
      // the JFR repository directories are under <basedir>/pid_<pid>
      String pid = fileName.startsWith("pid_") ? fileName.substring(4) : null;
      boolean isSelfPid = pid != null && pid.equals(PidHelper.getPid());
      shouldClean |= cleanSelf ? isSelfPid : !isSelfPid && !pidSet.contains(pid);
      if (shouldClean) {
        log.debug("Cleaning temporary location {}", dir);
      }
      return shouldClean ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      if (isTimedOut()) {
        log.debug("Cleaning task timed out");
        terminated = true;
        return FileVisitResult.TERMINATE;
      }
      cleanupTestHook.visitFile(file, attrs);
      try {
        if (Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Files.delete(file);
      } catch (NoSuchFileException ignored) {
        // another process has already cleaned it; ignore
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      if (isTimedOut()) {
        log.debug("Cleaning task timed out");
        terminated = true;
        return FileVisitResult.TERMINATE;
      }
      cleanupTestHook.visitFileFailed(file, exc);
      // do not log files/directories removed by another process running concurrently
      if (!(exc instanceof NoSuchFileException) && log.isDebugEnabled()) {
        log.debug("Failed to delete file {}", file, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      if (isTimedOut()) {
        log.debug("Cleaning task timed out");
        terminated = true;
        return FileVisitResult.TERMINATE;
      }
      cleanupTestHook.postVisitDirectory(dir, exc);
      if (exc instanceof NoSuchFileException) {
        return FileVisitResult.CONTINUE;
      }
      if (shouldClean) {
        try {
          Files.delete(dir);
        } catch (NoSuchFileException ignored) {
          // another process has already cleaned it; ignore
        }
        String fileName = dir.getFileName().toString();
        // reset the flag only if we are done cleaning the top-level directory
        shouldClean = !fileName.startsWith("pid_");
      }
      return FileVisitResult.CONTINUE;
    }
  }

  private final Path baseTempDir;
  private final Path tempDir;
  private final long cutoffSeconds;

  private final CompletableFuture<Void> cleanupTask;

  private final CleanupHook cleanupTestHook;

  /**
   * Get the singleton instance of the TempLocationManager. It will run the cleanup task in the
   * background.
   *
   * @return the singleton instance of the TempLocationManager
   */
  public static TempLocationManager getInstance() {
    return getInstance(false);
  }

  /**
   * Get the singleton instance of the TempLocationManager.
   *
   * @param waitForCleanup if true, wait for the cleanup task to finish before returning
   * @return the singleton instance of the TempLocationManager
   */
  static TempLocationManager getInstance(boolean waitForCleanup) {
    TempLocationManager instance = SingletonHolder.INSTANCE;
    if (waitForCleanup) {
      try {
        instance.waitForCleanup(5, TimeUnit.SECONDS);
      } catch (TimeoutException ignored) {

      }
    }
    return instance;
  }

  private TempLocationManager() {
    this(ConfigProvider.getInstance());
  }

  TempLocationManager(ConfigProvider configProvider) {
    this(configProvider, CleanupHook.EMPTY);
  }

  TempLocationManager(ConfigProvider configProvider, CleanupHook testHook) {
    cleanupTestHook = testHook;

    // In order to avoid racy attempts to clean up files which are currently being processed in a
    // JVM which is being shut down (the JVMs far in the shutdown routine may not be reported by
    // 'jps' but still can be eg. processing JFR chunks) we will not clean up any files not older
    // than '2 * PROFILING_UPLOAD_PERIOD' seconds.
    // The reasoning is that even if the file is created immediately at JVM startup once it is
    // 'PROFILING_UPLOAD_PERIOD' seconds old it gets processed to upload the final profile data and
    // this processing will not take longer than another `PROFILING_UPLOAD_PERIOD' seconds.
    // This is just an assumption but as long as the profiled application is working normally (eg.
    // OS is not stalling) this assumption will hold.
    cutoffSeconds =
        configProvider.getLong(
            ProfilingConfig.PROFILING_UPLOAD_PERIOD,
            ProfilingConfig.PROFILING_UPLOAD_PERIOD_DEFAULT);
    Path configuredTempDir =
        Paths.get(
            configProvider.getString(
                ProfilingConfig.PROFILING_TEMP_DIR, ProfilingConfig.PROFILING_TEMP_DIR_DEFAULT));
    if (!Files.exists(configuredTempDir)) {
      log.warn(
          "Base temp directory, as defined in '"
              + ProfilingConfig.PROFILING_TEMP_DIR
              + "' does not exist: "
              + configuredTempDir);
      throw new IllegalStateException(
          "Base temp directory, as defined in '"
              + ProfilingConfig.PROFILING_TEMP_DIR
              + "' does not exist: "
              + configuredTempDir);
    }

    String pid = PidHelper.getPid();

    baseTempDir = configuredTempDir.resolve("ddprof");
    baseTempDir.toFile().deleteOnExit();

    tempDir = baseTempDir.resolve("pid_" + pid);
    cleanupTask = CompletableFuture.runAsync(() -> cleanup(false));

    Thread selfCleanup =
        new Thread(
            () -> {
              try {
                waitForCleanup(1, TimeUnit.SECONDS);
              } catch (TimeoutException e) {
                log.info(
                    "Cleanup task timed out. {} temp directory might not have been cleaned up properly",
                    tempDir);
              } finally {
                cleanup(true);
              }
            },
            "Temp Location Manager Cleanup");
    Runtime.getRuntime().addShutdownHook(selfCleanup);
  }

  /**
   * Get the temporary directory for the current process. The directory will be removed at JVM exit.
   *
   * @return the temporary directory for the current process
   */
  public Path getTempDir() {
    return getTempDir(null);
  }

  /**
   * Get the temporary subdirectory for the current process. The directory will be removed at JVM
   * exit.
   *
   * @param subPath the relative subdirectory path, may be {@literal null}
   * @return the temporary subdirectory for the current process
   */
  public Path getTempDir(Path subPath) {
    return getTempDir(subPath, true);
  }

  /**
   * Get the temporary subdirectory for the current process.
   *
   * @param subPath the relative subdirectory path, may be {@literal null}
   * @param create if true, create the directory if it does not exist
   * @return the temporary directory for the current process
   * @throws IllegalStateException if the directory could not be created
   */
  public Path getTempDir(Path subPath, boolean create) {
    Path rslt =
        subPath != null && !subPath.toString().isEmpty() ? tempDir.resolve(subPath) : tempDir;
    if (create && !Files.exists(rslt)) {
      try {
        Files.createDirectories(
            rslt,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } catch (Exception e) {
        log.warn("Failed to create temp directory: {}", tempDir, e);
        throw new IllegalStateException("Failed to create temp directory: " + tempDir, e);
      }
    }
    return rslt;
  }

  /**
   * Walk the base temp directory recursively and remove all inactive per-process entries. No
   * timeout is applied.
   *
   * @param cleanSelf {@literal true} will call only this process' temp directory, {@literal false}
   *     only the other processes will be cleaned up
   * @return {@literal true} if cleanup fully succeeded or {@literal false} otherwise (eg.
   *     interruption etc.)
   */
  boolean cleanup(boolean cleanSelf) {
    return cleanup(cleanSelf, -1, TimeUnit.SECONDS);
  }

  /**
   * Walk the base temp directory recursively and remove all inactive per-process entries
   *
   * @param cleanSelf {@literal true} will call only this process' temp directory, {@literal false}
   *     only the other processes will be cleaned up
   * @param timeout the task timeout; may be {@literal -1} to signal no timeout
   * @param unit the task timeout unit
   * @return {@literal true} if cleanup fully succeeded or {@literal false} otherwise (timeout,
   *     interruption etc.)
   */
  boolean cleanup(boolean cleanSelf, long timeout, TimeUnit unit) {
    try {
      cleanupTestHook.onCleanupStart(cleanSelf, timeout, unit);
      CleanupVisitor visitor = new CleanupVisitor(cleanSelf, timeout, unit);
      Files.walkFileTree(baseTempDir, visitor);
      return !visitor.isTerminated();
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.warn("Unable to cleanup temp location {}", baseTempDir, e);
      } else {
        log.warn("Unable to cleanup temp location {}", baseTempDir);
      }
    }
    return false;
  }

  // accessible for tests
  void waitForCleanup(long timeout, TimeUnit unit) throws TimeoutException {
    try {
      cleanupTask.get(timeout, unit);
    } catch (InterruptedException e) {
      cleanupTask.cancel(true);
      Thread.currentThread().interrupt();
    } catch (TimeoutException e) {
      cleanupTask.cancel(true);
      throw e;
    } catch (ExecutionException e) {
      if (log.isDebugEnabled()) {
        log.debug("Failed to cleanup temp directory: {}", tempDir, e);
      } else {
        log.debug("Failed to cleanup temp directory: {}", tempDir);
      }
    }
  }
}
