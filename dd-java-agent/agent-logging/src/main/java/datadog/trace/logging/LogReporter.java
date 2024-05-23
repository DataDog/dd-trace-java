package datadog.trace.logging;

import static java.nio.file.Files.readAllBytes;

import datadog.trace.api.flare.TracerFlare;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.zip.ZipOutputStream;

public class LogReporter implements TracerFlare.Reporter {
  private static final LogReporter INSTANCE = new LogReporter();
  private static String logFile;

  public static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  @Override
  public void prepareForFlare() {
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
      logFile = null;
      try {
        boolean c = outputFile.createNewFile();
        if ((a || b) && c) {
          logFile = file;
          /// to remove if buffer
          PrintStreamWrapper.start(logFile);
        }
      } catch (IOException e) {
        // Nothing to do, file creation failed
      }

      // PrintStreamWrapper.start(logFile);
    }
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    try {
      if (logFile != null) {
        TracerFlare.addBinary(zip, "tracer.log", readAllBytes(Paths.get(logFile)));
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
  }

  @Override
  public void cleanupAfterFlare() {
    try {

      PrintStreamWrapper.clean();
      File outputFile = new File(logFile);
      if (outputFile.exists()) {
        boolean result = outputFile.delete();
        if (!result) {
          throw new RuntimeException("Failed to delete file: " + logFile);
        }
      }

    } catch (Exception e) {
      // not sure what to do here

    }
  }
}
