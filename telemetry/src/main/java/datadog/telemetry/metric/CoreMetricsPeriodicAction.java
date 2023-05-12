package datadog.telemetry.metric;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.trace.api.metrics.Instrument;
import datadog.trace.api.metrics.Metrics;
import datadog.trace.api.metrics.TelemetryMetrics;

import java.util.Iterator;

public class CoreMetricsPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
  Metrics metrics = TelemetryMetrics.getInstance();

  @Override
  public void doIteration(TelemetryService service) {
    Iterator<Instrument> updatedInstruments = this.metrics.updatedInstruments();
    while (updatedInstruments.hasNext()) {
      Instrument instrument = updatedInstruments.next();
      service.addMetric(new Metric()  // TODO Cache Metric instances and only update points
          .metric(instrument.getName())
          .common(instrument.isCommon())
          .tags(instrument.getTags())
          .type(Metric.TypeEnum.valueOf(instrument.getType()))
          .points(instrument.getValues()));
      instrument.reset();
    }
  }
}
