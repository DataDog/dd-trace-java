package datadog.trace.agent.tooling;

import datadog.trace.api.flare.TracerFlare;
import java.io.IOException;
import java.util.zip.ZipOutputStream;

public final class InstrumenterFlare implements TracerFlare.Reporter {
  private static final InstrumenterFlare INSTANCE = new InstrumenterFlare();

  public static void register() {
    TracerFlare.addReporter(INSTANCE);
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    TracerFlare.addText(zip, "instrumenter_state.txt", InstrumenterState.summary());
    TracerFlare.addText(zip, "instrumenter_metrics.txt", InstrumenterMetrics.summary());
  }
}
