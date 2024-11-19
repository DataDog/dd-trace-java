package com.datadog.profiling.controller;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.PidHelper;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

  private class CleanupVisitor implements FileVisitor<Path> {
    private boolean shouldClean;

    private final Set<String> pidSet = PidHelper.getJavaPids();

    private final boolean cleanSelf;
    private final Instant cutoff;

    CleanupVisitor(boolean cleanSelf) {
      this.cleanSelf = cleanSelf;
      this.cutoff = Instant.now().minus(cutoffSeconds, ChronoUnit.SECONDS);
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
      if (dir.equals(baseTempDir)) {
        return FileVisitResult.CONTINUE;
      }
      String fileName = dir.getFileName().toString();
      // the JFR repository directories are under <basedir>/pid_<pid>
      String pid = fileName.startsWith("pid_") ? fileName.substring(4) : null;
      boolean isSelfPid = pid != null && pid.equals(PidHelper.getPid());
      shouldClean |= (cleanSelf && isSelfPid) || (!cleanSelf && !pidSet.contains(pid));
      if (shouldClean) {
        log.debug("Cleaning temporary location {}", dir);
      }
      return shouldClean ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      if (Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) {
        return FileVisitResult.SKIP_SUBTREE;
      }
      Files.delete(file);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      if (log.isDebugEnabled()) {
        log.debug("Failed to delete file {}", file, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      if (shouldClean) {
        Files.delete(dir);
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
      instance.waitForCleanup();
    }
    return instance;
  }

  private TempLocationManager() {
    this(ConfigProvider.getInstance());
  }

  TempLocationManager(ConfigProvider configProvider) {
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
    tempDir = baseTempDir.resolve("pid_" + pid);
    cleanupTask = CompletableFuture.runAsync(() -> cleanup(false));

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    cleanupTask.join();
                  } finally {
                    cleanup(true);
                  }
                },
                "Temp Location Manager Cleanup"));
  }

  /**
   * Get the temporary directory for the current process. The directory will be removed at JVM exit.
   *
   * @return the temporary directory for the current process
   */
  public Path getTempDir() {
    return getTempDir(true);
  }

  /**
   * Get the temporary directory for the current process.
   *
   * @param create if true, create the directory if it does not exist
   * @return the temporary directory for the current process
   * @throws IllegalStateException if the directory could not be created
   */
  public Path getTempDir(boolean create) {
    if (create && !Files.exists(tempDir)) {
      try {
        Files.createDirectories(
            tempDir,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } catch (Exception e) {
        log.warn("Failed to create temp directory: {}", tempDir, e);
        throw new IllegalStateException("Failed to create temp directory: " + tempDir, e);
      }
    }
    return tempDir;
  }

  void cleanup(boolean cleanSelf) {
    try {
      Files.walkFileTree(baseTempDir, new CleanupVisitor(cleanSelf));
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.warn("Unable to cleanup temp location {}", baseTempDir, e);
      } else {
        log.warn("Unable to cleanup temp location {}", baseTempDir);
      }
    }
  }

  private void waitForCleanup() {
    cleanupTask.join();
  }
}
