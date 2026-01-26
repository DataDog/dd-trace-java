package datadog.trace.api.profiling;

import datadog.trace.api.flare.TracerFlare;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

public final class ProfilerFlareLogger implements TracerFlare.Reporter {
  private static final Logger log = LoggerFactory.getLogger(ProfilerFlareLogger.class);

  private static final class Singleton {
    private static final ProfilerFlareLogger INSTANCE = new ProfilerFlareLogger();
  }

  private final int REPORT_CAPACITY = 2 * 1024 * 1024; // 2MiB max in profiler reports
  private final List<String> flareReportLines = new ArrayList<>();
  private int usedReportCapacity = 0;

  // @VisibleForTesting
  ProfilerFlareLogger() {
    TracerFlare.addReporter(this);
  }

  public static ProfilerFlareLogger getInstance() {
    return Singleton.INSTANCE;
  }

  /**
   * Logs the message in slf4j format to the flare log storage.<br>
   *
   * @param msgFormat the message format in slf4j style
   * @param args the arguments for the message format
   * @return Returns {@literal true} if the message was stored for flare, {@literal false} otherwise
   */
  public boolean log(String msgFormat, Object... args) {
    // if something is important enough to store in flare, perhaps logging at WARN level is fine
    log.warn(msgFormat, args);

    FormattingTuple ft = MessageFormatter.arrayFormat(msgFormat, args);
    StringBuilder sb =
        new StringBuilder(Instant.now().atZone(ZoneOffset.UTC).toString())
            .append('\t')
            .append(ft.getMessage())
            .append('\n');
    if (ft.getThrowable() != null) {
      sb.append(ft.getThrowable());
    }
    synchronized (flareReportLines) {
      if (usedReportCapacity + sb.length() < REPORT_CAPACITY) {
        flareReportLines.add(sb.toString());
        usedReportCapacity += sb.length();
        return true;
      }
    }
    return false;
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    synchronized (flareReportLines) {
      if (!flareReportLines.isEmpty()) {
        TracerFlare.addText(zip, "profiler_log.txt", String.join("\n", flareReportLines));
      }
    }
  }

  @Override
  public void cleanupAfterFlare() {
    cleanup();
  }

  // @VisibleForTesting
  int getUsedReportCapacity() {
    return usedReportCapacity;
  }

  // @VisibleForTesting
  int getMaxReportCapacity() {
    return REPORT_CAPACITY;
  }

  // @VisibleForTesting
  int linesSize() {
    synchronized (flareReportLines) {
      return flareReportLines.size();
    }
  }

  void cleanup() {
    synchronized (flareReportLines) {
      flareReportLines.clear();
      usedReportCapacity = 0;
    }
  }
}
