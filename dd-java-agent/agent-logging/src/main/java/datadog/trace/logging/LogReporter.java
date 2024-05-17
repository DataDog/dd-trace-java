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
    TracerFlare.addBinary(zip, "tracer.log", PrintStreamWrapper.getBuffer());
  }

  @Override
  public void cleanupAfterFlare() {
    PrintStreamWrapper.clean();
  }
}
