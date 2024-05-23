package datadog.trace.logging;

import static java.nio.file.Files.readAllBytes;

import datadog.trace.api.flare.TracerFlare;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.zip.ZipOutputStream;

public class LogReporter implements TracerFlare.Reporter {
  private static final LogReporter INSTANCE = new LogReporter();
  private static final int MAX_LOGFILE_SIZE_MB = 15;
  private static final int MAX_LOGFILE_SIZE_BYTES = MAX_LOGFILE_SIZE_MB << 20;
  private static String tmpLogFile;
  private boolean flarePrepared;

  public static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  @Override
  public void prepareForFlare() {
    flarePrepared = true;
    String configuredLogFile = System.getProperty("org.slf4j.simpleLogger.logFile");

    if (configuredLogFile == null || configuredLogFile.isEmpty()) {
      long endMillis = System.currentTimeMillis();
      String randomID =
          UUID.randomUUID()
              .toString(); // we don't have access to runtime ID and what we want is to be sure the
      // files from different JVMs do not disturb each other
      String file =
          System.getProperty("java.io.tmpdir")
              + File.separator
              + "tracer"
              + "-"
              + endMillis
              + "-"
              + randomID
              + ".log";
      File outputFile = new File(file);
      File parentFile = outputFile.getParentFile();
      boolean a = parentFile.exists();
      boolean b = parentFile.mkdirs();
      tmpLogFile = null;
      try {
        boolean c = outputFile.createNewFile();
        if ((a || b) && c) {
          tmpLogFile = file;
          /// to remove if buffer
          PrintStreamWrapper.start(tmpLogFile);
        }
      } catch (IOException e) {
        // Nothing to do, file creation failed
      }

      // PrintStreamWrapper.start(logFile);
    }
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    String configuredLogFile = System.getProperty("org.slf4j.simpleLogger.logFile");
    if (configuredLogFile == null || configuredLogFile.isEmpty()) {
      // we should do this only when:
      // - no log file has been configured
      // - AND the tracer flare was requested by RC AND we didn't receive an agent config event as
      // DEBUG was not requested
      if (flarePrepared) {
        try {
          if (tmpLogFile != null) {
            TracerFlare.addBinary(zip, "tracer.log", readAllBytes(Paths.get(tmpLogFile)));
          } /* else {
              byte[] buffer = PrintStreamWrapper.getBuffer();
              if (buffer != null) {
                TracerFlare.addBinary(zip, "tracer.log", buffer);
              } else {
                TracerFlare.addText(zip, "tracer.log", "Problem collecting tracer log");
              }
            }*/
        } catch (Exception e) {
          TracerFlare.addText(zip, "tracer.log", "Problem collecting tracer log" + e.getMessage());
        }
      } else {
        TracerFlare.addText(zip, "tracer.log", "No tracer log file specified");
      }

    } else {
      Path path = Paths.get(configuredLogFile);
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
    flarePrepared = false;
    try {

      PrintStreamWrapper.clean();
      File outputFile = new File(tmpLogFile);
      if (outputFile.exists()) {
        boolean result = outputFile.delete();
        if (!result) {
          throw new RuntimeException("Failed to delete file: " + tmpLogFile);
        }
      }

    } catch (Exception e) {
      // not sure what to do here

    }
  }
}
