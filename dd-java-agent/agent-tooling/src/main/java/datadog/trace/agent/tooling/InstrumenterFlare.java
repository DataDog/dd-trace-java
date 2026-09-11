package datadog.trace.agent.tooling;

import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.internal.VisibleForTesting;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipOutputStream;

public final class InstrumenterFlare implements TracerFlare.Reporter {
  private static final InstrumenterFlare INSTANCE = new InstrumenterFlare();
  private static final int MAX_TRANSFORMATION_ERRORS = 64;
  private static final int MAX_ERROR_LENGTH = 4096;

  // Debug-only entries may contain application class and method names, so retain only a small,
  // bounded snapshot for an explicitly requested tracer flare.
  private final Map<String, Integer> transformationErrors = new LinkedHashMap<>();
  private long droppedTransformationErrors;

  public static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    TracerFlare.addText(zip, "instrumenter_state.txt", InstrumenterState.summary());
    TracerFlare.addText(zip, "instrumenter_metrics.txt", InstrumenterMetrics.summary());
    String errors = transformationErrors();
    if (!errors.isEmpty()) {
      TracerFlare.addText(zip, "instrumenter_errors.txt", errors);
    }
  }

  static void recordTransformationError(String error) {
    if (error.length() > MAX_ERROR_LENGTH) {
      error = error.substring(0, MAX_ERROR_LENGTH) + "...";
    }
    synchronized (INSTANCE.transformationErrors) {
      Integer count = INSTANCE.transformationErrors.get(error);
      if (count != null) {
        if (count < Integer.MAX_VALUE) {
          INSTANCE.transformationErrors.put(error, count + 1);
        }
      } else if (INSTANCE.transformationErrors.size() < MAX_TRANSFORMATION_ERRORS) {
        INSTANCE.transformationErrors.put(error, 1);
      } else {
        INSTANCE.droppedTransformationErrors++;
      }
    }
  }

  static String transformationErrors() {
    synchronized (INSTANCE.transformationErrors) {
      StringBuilder summary = new StringBuilder();
      for (Map.Entry<String, Integer> error : INSTANCE.transformationErrors.entrySet()) {
        summary
            .append("count=")
            .append(error.getValue())
            .append(' ')
            .append(error.getKey())
            .append('\n');
      }
      if (INSTANCE.droppedTransformationErrors > 0) {
        summary
            .append("dropped_error_count=")
            .append(INSTANCE.droppedTransformationErrors)
            .append('\n');
      }
      return summary.toString();
    }
  }

  @VisibleForTesting
  static void resetTransformationErrors() {
    synchronized (INSTANCE.transformationErrors) {
      INSTANCE.transformationErrors.clear();
      INSTANCE.droppedTransformationErrors = 0;
    }
  }
}
