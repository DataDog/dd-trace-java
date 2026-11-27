package datadog.trace.util;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import datadog.environment.SystemProperties;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.profiling.ProfilerFlareLogger;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.config.inversion.ConfigHelper;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
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
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages profiling files beneath a fresh directory for each JVM. Startup cleanup removes stale
 * files belonging to exited JVMs from directories owned by the same OS user.
 */
public final class TempLocationManager {
  private static final Logger log = LoggerFactory.getLogger(TempLocationManager.class);
  private static final String TEMPDIR_PREFIX = "pid_";

  private static final class SingletonHolder {
    private static final TempLocationManager INSTANCE = new TempLocationManager();
  }

  interface CleanupHook {
    CleanupHook EMPTY = new CleanupHook() {};

    default FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs, boolean timeout)
        throws IOException {
      return timeout ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    default FileVisitResult visitFile(Path file, BasicFileAttributes attrs, boolean timeout)
        throws IOException {
      return timeout ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    default FileVisitResult visitFileFailed(Path file, IOException exc, boolean timeout)
        throws IOException {
      return timeout ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    default FileVisitResult postVisitDirectory(Path dir, IOException exc, boolean timeout)
        throws IOException {
      return timeout ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    default void onCleanupStart(long timeout, TimeUnit unit) {}
  }

  private final class CleanupVisitor implements FileVisitor<Path> {
    private boolean shouldClean;
    private Path cleanupBase;

    private Set<String> pidSet;

    private final long cutoffMillis;
    private final long timeoutTargetMillis;

    private boolean terminated = false;

    CleanupVisitor(long timeout, TimeUnit unit) {
      long now = timeSource.getCurrentTimeMillis();
      this.cutoffMillis = now - TimeUnit.SECONDS.toMillis(cutoffSeconds);
      this.timeoutTargetMillis = timeout > -1 ? now + unit.toMillis(timeout) : Long.MAX_VALUE;
    }

    boolean isTerminated() {
      return terminated;
    }

    void setBase(Path cleanupBase) {
      this.cleanupBase = cleanupBase;
      shouldClean = false;
    }

    private boolean isTimedOut() {
      return timeSource.getCurrentTimeMillis() > timeoutTargetMillis;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
        throws IOException {
      if (isTimedOut()) {
        log.debug("Cleaning task timed out");
        terminated = true;
        cleanupTestHook.preVisitDirectory(dir, attrs, true);
        return FileVisitResult.TERMINATE;
      }

      cleanupTestHook.preVisitDirectory(dir, attrs, false);

      if (dir.equals(cleanupBase)) {
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
        cleanupTestHook.visitFile(file, attrs, true);
        return FileVisitResult.TERMINATE;
      }
      cleanupTestHook.visitFile(file, attrs, false);
      try {
        if (Files.getLastModifiedTime(file).toMillis() > cutoffMillis) {
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
        cleanupTestHook.visitFileFailed(file, exc, true);
        return FileVisitResult.TERMINATE;
      }
      cleanupTestHook.visitFileFailed(file, exc, false);
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
        cleanupTestHook.postVisitDirectory(dir, exc, true);
        return FileVisitResult.TERMINATE;
      }
      cleanupTestHook.postVisitDirectory(dir, exc, false);
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

  private static final Set<PosixFilePermission> GROUP_WORLD_BITS =
      EnumSet.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.GROUP_EXECUTE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE,
          PosixFilePermission.OTHERS_EXECUTE);

  private final boolean isPosixFs;
  private final Path configuredTempDir;
  private final String baseTempDirName;
  private final Path baseTempDir;
  private final Path tempDir;
  private final long cutoffSeconds;
  private final UserPrincipal expectedOwner;

  private final CleanupTask cleanupTask = new CleanupTask();
  private final CleanupHook cleanupTestHook;
  private final TimeSource timeSource;

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
    this(configProvider, true, CleanupHook.EMPTY, SystemTimeSource.INSTANCE);
  }

  TempLocationManager(
      ConfigProvider configProvider, boolean runStartupCleanup, CleanupHook testHook) {
    this(configProvider, runStartupCleanup, testHook, SystemTimeSource.INSTANCE);
  }

  TempLocationManager(
      ConfigProvider configProvider,
      boolean runStartupCleanup,
      CleanupHook testHook,
      TimeSource timeSource) {
    cleanupTestHook = testHook;
    this.timeSource = timeSource;

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
    configuredTempDir =
        Paths.get(
            configProvider.getString(
                ProfilingConfig.PROFILING_TEMP_DIR, ProfilingConfig.PROFILING_TEMP_DIR_DEFAULT));
    if (!Files.exists(configuredTempDir)) {
      ProfilerFlareLogger.getInstance()
          .log("Base temp directory, as defined in '{}' does not exist.", configuredTempDir);
      throw new IllegalStateException(
          "Base temp directory, as defined in '"
              + ProfilingConfig.PROFILING_TEMP_DIR
              + "' does not exist: "
              + configuredTempDir);
    }

    String pid = PidHelper.getPid();

    baseTempDirName = getBaseTempDirName();
    try {
      // A fresh parent avoids reusing directories left behind by earlier JVMs.
      baseTempDir =
          isPosixFs
              ? Files.createTempDirectory(
                  configuredTempDir,
                  baseTempDirName + "_",
                  PosixFilePermissions.asFileAttribute(
                      PosixFilePermissions.fromString("rwx------")))
              : Files.createTempDirectory(configuredTempDir, baseTempDirName + "_");
      expectedOwner = isPosixFs ? Files.getOwner(baseTempDir, NOFOLLOW_LINKS) : null;
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to create profiler temp directory in " + configuredTempDir, e);
    }
    baseTempDir.toFile().deleteOnExit();

    tempDir = baseTempDir.resolve(TEMPDIR_PREFIX + pid);
    createTempDir(tempDir);
    if (runStartupCleanup) {
      // do not execute the background cleanup task when running in tests
      AgentTaskScheduler.get().execute(cleanupTask);
    }
  }

  @VisibleForTesting
  static String getBaseTempDirName() {
    String userName = SystemProperties.get("user.name");
    // unlikely, but fall-back to system env based user name
    userName = userName == null ? ConfigHelper.env("USER") : userName;
    // make sure we do not have any illegal characters in the user name
    userName =
        userName != null ? userName.replace('.', '_').replace('/', '_').replace(' ', '_') : null;
    return "ddprof" + (userName != null ? "_" + userName : "");
  }

  /**
   * Gets the temporary directory for the current process. Stale files may be removed by startup
   * cleanup in a later JVM.
   *
   * @return the temporary directory for the current process
   */
  public Path getTempDir() {
    return getTempDir(null);
  }

  /**
   * Gets a temporary subdirectory for the current process.
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
    } else if (isPosixFs && Files.exists(rslt)) {
      validateOwnedSecureDir(rslt);
    }
    return rslt;
  }

  /**
   * Removes stale per-process entries from profiling directories. No timeout is applied.
   *
   * @return {@literal true} if cleanup fully succeeded or {@literal false} otherwise (eg.
   *     interruption etc.)
   */
  boolean cleanup() {
    return cleanup(-1, TimeUnit.SECONDS);
  }

  /**
   * Removes stale per-process entries from profiling directories.
   *
   * @param timeout the task timeout; may be {@literal -1} to signal no timeout
   * @param unit the task timeout unit
   * @return {@literal true} if cleanup fully succeeded or {@literal false} otherwise (timeout,
   *     interruption etc.)
   */
  boolean cleanup(long timeout, TimeUnit unit) {
    try {
      cleanupTestHook.onCleanupStart(timeout, unit);
      CleanupVisitor visitor = new CleanupVisitor(timeout, unit);
      boolean success = true;
      String prefix = baseTempDirName + "_";
      try (DirectoryStream<Path> candidates =
          Files.newDirectoryStream(
              configuredTempDir,
              path -> {
                String name = path.getFileName().toString();
                return name.equals(baseTempDirName) || name.startsWith(prefix);
              })) {
        for (Path candidate : candidates) {
          if (visitor.isTimedOut()) {
            return false;
          }
          try {
            if (!isTrustedCleanupBase(candidate)) {
              continue;
            }
            boolean removeBase =
                !candidate.equals(baseTempDir)
                    && Files.getLastModifiedTime(candidate, NOFOLLOW_LINKS).toMillis()
                        <= visitor.cutoffMillis;
            visitor.setBase(candidate);
            Files.walkFileTree(candidate, visitor);
            if (visitor.isTerminated()) {
              return false;
            }
            if (removeBase) {
              try {
                Files.delete(candidate);
              } catch (DirectoryNotEmptyException ignored) {
                // An active JVM or recent files still use this directory.
              }
            }
          } catch (NoSuchFileException ignored) {
            // Another JVM has already cleaned this directory.
          } catch (IOException e) {
            log.debug("Unable to cleanup temp location {}", candidate, e);
            success = false;
          }
        }
      }
      return success;
    } catch (IOException e) {
      log.debug("Unable to scan profiling temp locations in {}", configuredTempDir, e);
    }
    return false;
  }

  @VisibleForTesting
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

  private boolean isTrustedCleanupBase(Path dir) throws IOException {
    if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) {
      return false;
    }
    return !isPosixFs
        || (expectedOwner.equals(Files.getOwner(dir, NOFOLLOW_LINKS))
            && Files.getPosixFilePermissions(dir, NOFOLLOW_LINKS).stream()
                .noneMatch(GROUP_WORLD_BITS::contains));
  }

  /**
   * Validates that the given directory is owned by the current JVM user and has no group or world
   * permission bits set (effective {@code 0700}). Only enforced on POSIX file systems.
   *
   * @throws IllegalStateException if the directory fails the ownership or permission check
   */
  private void validateOwnedSecureDir(Path dir) {
    if (!isPosixFs) {
      return;
    }
    try {
      if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Untrusted temp directory: " + dir);
      }
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(dir, NOFOLLOW_LINKS);
      UserPrincipal owner = Files.getOwner(dir, NOFOLLOW_LINKS);
      boolean hasGroupWorldBits = perms.stream().anyMatch(GROUP_WORLD_BITS::contains);
      boolean wrongOwner = !expectedOwner.equals(owner);
      if (hasGroupWorldBits || wrongOwner) {
        ProfilerFlareLogger.getInstance()
            .log(
                "Refusing to use temp directory {} owned by {} with perms {}",
                dir,
                owner,
                PosixFilePermissions.toString(perms));
        throw new IllegalStateException("Untrusted temp directory: " + dir);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (IOException e) {
      ProfilerFlareLogger.getInstance()
          .log("Failed to validate temp directory ownership/permissions for {}", dir);
      throw new IllegalStateException("Untrusted temp directory: " + dir, e);
    }
  }

  private void createTempDir(Path tempDir) {
    String msg = "Failed to create temp directory: " + tempDir;
    try {
      if (isPosixFs) {
        // Check existing components before creating subdirectories.
        Path current = baseTempDir;
        if (Files.exists(current)) {
          validateOwnedSecureDir(current);
          // Walk the relative path components between baseTempDir and tempDir
          Path rel = baseTempDir.relativize(tempDir);
          for (int i = 0; i < rel.getNameCount(); i++) {
            current = current.resolve(rel.getName(i));
            if (Files.exists(current)) {
              validateOwnedSecureDir(current);
            }
          }
        }
        Files.createDirectories(
            tempDir,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        validateOwnedSecureDir(tempDir);
      } else {
        Files.createDirectories(tempDir);
      }
    } catch (IllegalStateException e) {
      throw e;
    } catch (IOException e) {
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
            ProfilerFlareLogger.getInstance()
                .log(
                    "Failed to create temp directory: {}, offender: {}, permissions: {}",
                    msg,
                    failedDir,
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(failedDir)),
                    e);
          } else {
            ProfilerFlareLogger.getInstance().log(msg);
          }
        } catch (IOException ignored) {
          // should not happen, but let's ignore it anyway
        }
        throw new IllegalStateException(msg, e);
      } else {
        ProfilerFlareLogger.getInstance().log(msg, e);
        throw new IllegalStateException(msg, e);
      }
    }
  }
}
