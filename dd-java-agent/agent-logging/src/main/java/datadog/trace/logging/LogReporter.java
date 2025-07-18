package datadog.trace.logging;

import static java.nio.file.Files.readAllBytes;

import datadog.environment.SystemProperties;
import datadog.trace.api.Config;
import datadog.trace.api.flare.TracerFlare;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipOutputStream;

public class LogReporter implements TracerFlare.Reporter {
  private static final LogReporter INSTANCE = new LogReporter();
  private static final int MAX_LOGFILE_SIZE_MB = 15;
  public static final int MAX_LOGFILE_SIZE_BYTES = MAX_LOGFILE_SIZE_MB << 20;
  private Path capturedLogPath;
  private boolean isFlarePrepared;
  private static File configuredLogFile;
  private static PrintStreamWrapper wrappedPrintStream;

  private LogReporter() {}

  public static void register(PrintStreamWrapper printStreamWrapper) {
    wrappedPrintStream = printStreamWrapper;
    register();
  }

  public static void register(File configuredFile) {
    configuredLogFile = configuredFile;
    register();
  }

  private static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  @Override
  public void prepareForFlare() {
    isFlarePrepared = true;
    if (wrappedPrintStream != null) {
      long endMillis = System.currentTimeMillis();
      String captureFilename =
          "tracer" + "-" + Config.get().getRuntimeId() + "-" + endMillis + ".log";
      try {
        Path tempPath = Paths.get(SystemProperties.get("java.io.tmpdir"), captureFilename);
        Path parentPath = tempPath.getParent();
        if (parentPath != null && !Files.isDirectory(parentPath)) {
          Files.createDirectories(parentPath);
        }
        capturedLogPath = Files.createFile(tempPath);
        wrappedPrintStream.startCapturing(capturedLogPath);
      } catch (IOException e) {
        // Nothing to do, file creation failed, we don't have access to log yet
      }
    }
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    if (configuredLogFile == null) {
      if (isFlarePrepared) {
        try {
          if (capturedLogPath != null) {
            TracerFlare.addBinary(zip, "tracer.log", readAllBytes(capturedLogPath));
          }
        } catch (Exception e) {
          TracerFlare.addText(
              zip, "tracer.log", "Problem reading temporary tracer log file: " + e.getMessage());
        }
      } else {
        TracerFlare.addText(
            zip, "tracer.log", "No tracer log file specified and no prepare flare event received");
      }

    } else {
      Path path = Paths.get(configuredLogFile.getPath());
      if (Files.exists(path)) {
        try {
          long size = Files.size(path);
          if (size > MAX_LOGFILE_SIZE_BYTES) {
            int maxSizeOfSplit = MAX_LOGFILE_SIZE_BYTES / 2;
            File originalFile = new File(path.toString());
            try (RandomAccessFile ras = new RandomAccessFile(originalFile, "r")) {
              final byte[] buffer = new byte[maxSizeOfSplit];
              ras.readFully(buffer);
              TracerFlare.addBinary(zip, "tracer_begin.log", buffer);
              ras.seek(size - maxSizeOfSplit);
              ras.readFully(buffer);
              TracerFlare.addBinary(zip, "tracer_end.log", buffer);
            }
          } else {
            TracerFlare.addBinary(zip, "tracer.log", readAllBytes(path));
          }
        } catch (Throwable e) {
          TracerFlare.addText(zip, "tracer.log", "Problem collecting tracer log: " + e);
        }
      }
    }
  }

  @Override
  public void cleanupAfterFlare() {
    isFlarePrepared = false;
    if (wrappedPrintStream != null) {
      try {
        wrappedPrintStream.stopCapturing();
        File outputFile = capturedLogPath.toFile();
        if (outputFile.exists()) {
          if (!outputFile.delete()) {
            throw new RuntimeException("Failed to delete file: " + capturedLogPath);
          }
        }
      } catch (Exception e) {
        // not sure what to do here
      }
      capturedLogPath = null;
    }
  }
}
