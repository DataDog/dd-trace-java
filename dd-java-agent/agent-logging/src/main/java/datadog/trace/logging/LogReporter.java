package datadog.trace.logging;

import datadog.trace.api.flare.TracerFlare;
import java.io.IOException;
import java.util.zip.ZipOutputStream;

public class LogReporter implements TracerFlare.Reporter {

  private static final LogReporter INSTANCE = new LogReporter();

  public static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  @Override
  public void prepareForFlare() {
    String logFile = System.getProperty("org.slf4j.simpleLogger.logFile");
    if (logFile == null || logFile.isEmpty()) {
      PrintStreamWrapper.start();
    }
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    byte[] buffer = PrintStreamWrapper.getBuffer();
    if (buffer != null) {
      TracerFlare.addBinary(zip, "tracer.log", buffer);
    } else {
      TracerFlare.addText(zip, "tracer.log", "Problem collecting tracer log");
    }
  }

  @Override
  public void cleanupAfterFlare() {
    PrintStreamWrapper.clean();
  }
}
