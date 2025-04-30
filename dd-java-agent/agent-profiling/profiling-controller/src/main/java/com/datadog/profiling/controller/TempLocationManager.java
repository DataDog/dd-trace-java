package com.datadog.profiling.controller;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

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
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    default void onCleanupStart(long timeout, TimeUnit unit) {}
  }

  private final class CleanupVisitor implements FileVisitor<Path> {
    private boolean shouldClean;

    private Set<String> pidSet;

    private final Instant cutoff;
    private final Instant timeoutTarget;

    private boolean terminated = false;

    CleanupVisitor(long timeout, TimeUnit unit) {
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
      String pid = fileName.startsWith(TEMPDIR_PREFIX) ? fileName.substring(4) : null;
      boolean isSelfPid = pid != null && pid.equals(PidHelper.getPid());
      if (!isSelfPid) {
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
        cleanup();
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

  private final boolean isPosixFs;
  private final Path baseTempDir;
  private final Path tempDir;
  private final long cutoffSeconds;

  private final CleanupTask cleanupTask = new CleanupTask();
  private final CleanupHook cleanupTestHook;

  private final Map<Path, Path> ignoredPaths = new ConcurrentHashMap<>();

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

    Set<String> supportedViews = FileSystems.getDefault().supportedFileAttributeViews();
    isPosixFs = supportedViews.contains("posix");

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

    createTempDir(tempDir);
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
      createTempDir(rslt);
    }
    return rslt;
  }

  public void ignore(Path path) {
    if (path.startsWith(baseTempDir)) {
      // ignore the path if it is a child of the base temp directory
      ignoredPaths.put(path, path);
    } else {
      log.debug(
          "Path {} which is not a child of the base temp directory {} can not be ignored",
          path,
          baseTempDir);
    }
  }

  /**
   * Walk the base temp directory recursively and remove all inactive per-process entries. No
   * timeout is applied.
   *
   * @return {@literal true} if cleanup fully succeeded or {@literal false} otherwise (eg.
   *     interruption etc.)
   */
  boolean cleanup() {
    return cleanup(-1, TimeUnit.SECONDS);
  }

  /**
   * Walk the base temp directory recursively and remove all inactive per-process entries
   *
   * @param timeout the task timeout; may be {@literal -1} to signal no timeout
   * @param unit the task timeout unit
   * @return {@literal true} if cleanup fully succeeded or {@literal false} otherwise (timeout,
   *     interruption etc.)
   */
  boolean cleanup(long timeout, TimeUnit unit) {
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
      cleanupTestHook.onCleanupStart(timeout, unit);
      CleanupVisitor visitor = new CleanupVisitor(timeout, unit);
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

  private void createTempDir(Path tempDir) {
    String msg = "Failed to create temp directory: " + tempDir;
    try {
      if (isPosixFs) {
        Files.createDirectories(
            tempDir,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } else {
        Files.createDirectories(tempDir);
      }
    } catch (IOException e) {
      log.error("Failed to create temp directory {}", tempDir, e);
      // if on a posix fs, let's check the expected permissions
      // we will find the first offender not having the expected permissions and fail the check
      if (isPosixFs) {
        // take the first subfolder below the base temp dir
        // we can wave the checks for tempDir being a subdir of baseTempDir because that's how it is
        // created
        Path root = baseTempDir.resolve(baseTempDir.relativize(tempDir).getName(0));
        try {
          AtomicReference<Path> failed = new AtomicReference<>();
          Files.walkFileTree(
              root,
              new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                  Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir);
                  if (!perms.contains(PosixFilePermission.OWNER_READ)
                      || !perms.contains(PosixFilePermission.OWNER_WRITE)
                      || !perms.contains(PosixFilePermission.OWNER_EXECUTE)) {
                    failed.set(dir);
                    return FileVisitResult.TERMINATE;
                  }
                  return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  return FileVisitResult.SKIP_SIBLINGS;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException {
                  return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                  return FileVisitResult.CONTINUE;
                }
              });
          Path failedDir = failed.get();

          if (failedDir != null) {
            msg +=
                " (offender: "
                    + failedDir
                    + ", permissions: "
                    + PosixFilePermissions.toString(Files.getPosixFilePermissions(failedDir))
                    + ")";
            log.warn(SEND_TELEMETRY, msg, e);
          }
        } catch (IOException ignored) {
          // should not happen, but let's ignore it anyway
        }
        throw new IllegalStateException(msg, e);
      } else {
        log.warn(SEND_TELEMETRY, msg, e);
        throw new IllegalStateException(msg, e);
      }
    }
  }
}
