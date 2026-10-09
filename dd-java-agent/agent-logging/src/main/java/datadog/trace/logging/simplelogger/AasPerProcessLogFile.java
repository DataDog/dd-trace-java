package datadog.trace.logging.simplelogger;

import datadog.environment.EnvironmentVariables;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Repro-only (SVLS-9057): in Azure App Service, overlapping worker processes run several JVMs that
 * share one configured log file, and each new JVM truncates it. This gives each JVM its own file,
 * the same way the .NET tracer does: the PID goes in the name, the file rolls over at a size limit,
 * and old per-process files are deleted at startup.
 *
 * <p>Settings mirror the .NET tracer: DD_TRACE_LOGFILE_RETENTION_DAYS (default 32, 0 disables
 * cleanup) and DD_MAX_LOGFILE_SIZE in bytes (default 10 MB).
 */
@SuppressForbidden
final class AasPerProcessLogFile {
  private static final int DEFAULT_RETENTION_DAYS = 32;
  private static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024 * 1024;

  private AasPerProcessLogFile() {}

  static boolean isEnabled() {
    String aas = EnvironmentVariables.get("DD_AZURE_APP_SERVICES");
    return "1".equals(aas) || "true".equalsIgnoreCase(aas);
  }

  /** Opens the per-process log file and starts the cleanup of old per-process files. */
  static RollingFileOutputStream open(File configuredFile) throws IOException {
    String pid = currentPid();
    String name = configuredFile.getName();
    int dot = name.lastIndexOf('.');
    String stem = dot > 0 ? name.substring(0, dot) : name;
    String extension = dot > 0 ? name.substring(dot) : "";
    File directory = configuredFile.getAbsoluteFile().getParentFile();

    startCleanup(directory, stem, extension);
    return new RollingFileOutputStream(directory, stem + "-" + pid, extension, maxSizeBytes());
  }

  static String currentPid() {
    try {
      // Java 9+; reflection keeps this class loadable on Java 8.
      Class<?> handle = Class.forName("java.lang.ProcessHandle");
      Object current = handle.getMethod("current").invoke(null);
      return String.valueOf(handle.getMethod("pid").invoke(current));
    } catch (Throwable ignored) {
      // Java 8
    }
    try {
      String vmName = ManagementFactory.getRuntimeMXBean().getName();
      int at = vmName.indexOf('@');
      if (at > 0) {
        return vmName.substring(0, at);
      }
    } catch (Throwable ignored) {
      // fall through
    }
    return "unknown";
  }

  private static long maxSizeBytes() {
    return parseLong(EnvironmentVariables.get("DD_MAX_LOGFILE_SIZE"), DEFAULT_MAX_SIZE_BYTES);
  }

  private static int retentionDays() {
    return (int)
        parseLong(
            EnvironmentVariables.get("DD_TRACE_LOGFILE_RETENTION_DAYS"), DEFAULT_RETENTION_DAYS);
  }

  private static long parseLong(String value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static void startCleanup(final File directory, String stem, String extension) {
    final int days = retentionDays();
    if (days <= 0 || directory == null) {
      return;
    }
    // Only this tracer's per-process files: <stem>-<pid>.<ext> and rolled <stem>-<pid>_<n>.<ext>
    final Pattern ownFiles =
        Pattern.compile(Pattern.quote(stem) + "-\\d+(_\\d+)?" + Pattern.quote(extension));
    Thread cleanup =
        new Thread(
            () -> {
              long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
              try {
                File[] files = directory.listFiles();
                if (files == null) {
                  return;
                }
                for (File file : files) {
                  if (ownFiles.matcher(file.getName()).matches()
                      && file.lastModified() < cutoff
                      && !file.delete()) {
                    return; // stop at the first failure, like the .NET tracer
                  }
                }
              } catch (Throwable ignored) {
                // stop quietly at the first IO problem
              }
            },
            "dd-log-file-cleanup");
    cleanup.setDaemon(true);
    cleanup.start();
  }

  /** Appends to base.ext, then base_001.ext, base_002.ext, ... once each reaches maxSizeBytes. */
  static final class RollingFileOutputStream extends OutputStream {
    private final File directory;
    private final String base;
    private final String extension;
    private final long maxSizeBytes;
    private FileOutputStream current;
    private long currentSize;
    private int sequence;

    RollingFileOutputStream(File directory, String base, String extension, long maxSizeBytes)
        throws IOException {
      this.directory = directory;
      this.base = base;
      this.extension = extension;
      this.maxSizeBytes = maxSizeBytes;
      if (directory != null) {
        directory.mkdirs();
      }
      openNext();
    }

    File currentFile() {
      String suffix = sequence == 0 ? "" : String.format("_%03d", sequence);
      return new File(directory, base + suffix + extension);
    }

    private void openNext() throws IOException {
      File file = currentFile();
      current = new FileOutputStream(file, true);
      currentSize = file.length();
    }

    private void rollIfNeeded(int incoming) throws IOException {
      if (maxSizeBytes > 0 && currentSize > 0 && currentSize + incoming > maxSizeBytes) {
        current.close();
        sequence++;
        openNext();
      }
    }

    @Override
    public synchronized void write(int b) throws IOException {
      rollIfNeeded(1);
      current.write(b);
      currentSize++;
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
      rollIfNeeded(len);
      current.write(b, off, len);
      currentSize += len;
    }

    @Override
    public synchronized void flush() throws IOException {
      current.flush();
    }

    @Override
    public synchronized void close() throws IOException {
      current.close();
    }
  }
}
