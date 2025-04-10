package com.datadog.profiling.controller;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.PidHelper;
import java.io.IOException;
import java.nio.file.FileSystems;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
  private static final Pattern JFR_DIR_PATTERN =
      Pattern.compile("\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{6}");
  private static final String TEMPDIR_PREFIX = "pid_";

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

  private final class CleanupVisitor implements FileVisitor<Path> {
    private boolean shouldClean;

    private Set<String> pidSet;

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
      if (cleanSelf && JFR_DIR_PATTERN.matcher(dir.getFileName().toString()).matches()) {
        // do not delete JFR repository on 'self-cleanup' - it conflicts with the JFR's own cleanup
        return FileVisitResult.SKIP_SUBTREE;
      }

      cleanupTestHook.preVisitDirectory(dir, attrs);

      if (dir.equals(baseTempDir)) {
        return FileVisitResult.CONTINUE;
      }
      String fileName = dir.getFileName().toString();
      // the JFR repository directories are under <basedir>/pid_<pid>
      String pid = fileName.startsWith(TEMPDIR_PREFIX) ? fileName.substring(4) : null;
      boolean isSelfPid = pid != null && pid.equals(PidHelper.getPid());
      if (cleanSelf) {
        shouldClean |= isSelfPid;
      } else if (!isSelfPid) {
        if (pidSet == null) {
          pidSet = PidHelper.getJavaPids(); // only fork jps when required
        }
        shouldClean |= !pidSet.contains(pid);
      }
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
        shouldClean = !fileName.startsWith(TEMPDIR_PREFIX);
      }
      return FileVisitResult.CONTINUE;
    }
  }

  private final class CleanupTask implements Runnable {
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile Throwable throwable = null;

    @Override
    public void run() {
      try {
        cleanup(false);
      } catch (OutOfMemoryError oom) {
        throw oom;
      } catch (Throwable t) {
        throwable = t;
      } finally {
        latch.countDown();
      }
    }

    boolean await(long timeout, TimeUnit unit) throws Throwable {
      boolean ret = latch.await(timeout, unit);
      if (throwable != null) {
        throw throwable;
      }
      return ret;
    }
  }

  private final Path baseTempDir;
  private final Path tempDir;
  private final long cutoffSeconds;

  private final CleanupTask cleanupTask = new CleanupTask();
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
      instance.waitForCleanup(5, TimeUnit.SECONDS);
    }
    return instance;
  }

  private TempLocationManager() {
    this(ConfigProvider.getInstance());
  }

  TempLocationManager(ConfigProvider configProvider) {
    this(configProvider, true, CleanupHook.EMPTY);
  }

  TempLocationManager(
      ConfigProvider configProvider, boolean runStartupCleanup, CleanupHook testHook) {
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
          SEND_TELEMETRY,
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

    baseTempDir = configuredTempDir.resolve(getBaseTempDirName());
    baseTempDir.toFile().deleteOnExit();

    tempDir = baseTempDir.resolve(TEMPDIR_PREFIX + pid);
    if (runStartupCleanup) {
      // do not execute the background cleanup task when running in tests
      AgentTaskScheduler.INSTANCE.execute(cleanupTask);
    }

    Thread selfCleanup =
        new Thread(
            AGENT_THREAD_GROUP,
            () -> {
              if (!waitForCleanup(1, TimeUnit.SECONDS)) {
                log.info(
                    "Cleanup task timed out. {} temp directory might not have been cleaned up properly",
                    tempDir);
              }
              cleanup(true);
            },
            "Temp Location Manager Cleanup");
    Runtime.getRuntime().addShutdownHook(selfCleanup);
  }

  // @VisibleForTesting
  static String getBaseTempDirName() {
    String userName = System.getProperty("user.name");
    // unlikely, but fall-back to system env based user name
    userName = userName == null ? System.getenv("USER") : userName;
    // make sure we do not have any illegal characters in the user name
    userName =
        userName != null ? userName.replace('.', '_').replace('/', '_').replace(' ', '_') : null;
    return "ddprof" + (userName != null ? "_" + userName : "");
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
        Set<String> supportedViews = FileSystems.getDefault().supportedFileAttributeViews();
        if (supportedViews.contains("posix")) {
          Files.createDirectories(
              rslt,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
          // non-posix, eg. Windows - let's rely on the created folders being world-writable
          Files.createDirectories(rslt);
        }

      } catch (Exception e) {
        log.warn(SEND_TELEMETRY, "Failed to create temp directory: {}", tempDir, e);
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
      if (!Files.exists(baseTempDir)) {
        // not even the main temp location exists; nothing to clean up
        return true;
      }
      try (Stream<Path> paths = Files.walk(baseTempDir)) {
        if (paths.noneMatch(
            path ->
                Files.isDirectory(path)
                    && path.getFileName().toString().startsWith(TEMPDIR_PREFIX))) {
          // nothing to clean up; bail out early
          return true;
        }
      }
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
  boolean waitForCleanup(long timeout, TimeUnit unit) {
    try {
      return cleanupTask.await(timeout, unit);
    } catch (InterruptedException e) {
      log.debug("Temp directory cleanup was interrupted");
      Thread.currentThread().interrupt();
    } catch (Throwable t) {
      if (log.isDebugEnabled()) {
        log.debug("Failed to cleanup temp directory: {}", tempDir, t);
      } else {
        log.debug("Failed to cleanup temp directory: {}", tempDir);
      }
    }
    return false;
  }

  // accessible for tests
  void createDirStructure() throws IOException {
    Files.createDirectories(baseTempDir);
  }
}
