package datadog.telemetry.metric;

import datadog.trace.api.metrics.Instrument;
import datadog.trace.api.metrics.Metrics;
import datadog.trace.api.metrics.TelemetryMetrics;
import datadog.trace.api.telemetry.MetricCollector;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class CoreMetricsPeriodicAction extends MetricPeriodicAction {
  private final CoreMetricCollector collector = new CoreMetricCollector();

  @NonNull
  @Override
  public MetricCollector<MetricCollector.Metric> collector() {
    return this.collector;
  }

  private static class CoreMetricCollector implements MetricCollector<MetricCollector.Metric> {
    private final Metrics metrics;
    private Collection<MetricCollector.Metric> coreMetrics;

    public CoreMetricCollector() {
      this.metrics = TelemetryMetrics.getInstance();
      this.coreMetrics = new ArrayList<>();
    }

    @Override
    public void prepareMetrics() {
      Iterator<Instrument> updatedInstruments = this.metrics.updatedInstruments();
      while (updatedInstruments.hasNext()) {
        Instrument instrument = updatedInstruments.next();
        if ("COUNT".equals(instrument.getType())) { // Only counter supported
          this.coreMetrics.add(
              new CoreMetric(
                  instrument.getName(),
                  instrument.getValue().longValue(), // Drop precision to long for counter
                  instrument.getTags()));
        }
        instrument.reset();
      }
    }

    @Override
    public Collection<MetricCollector.Metric> drain() {
      Collection<MetricCollector.Metric> drained = this.coreMetrics;
      this.coreMetrics = new ArrayList<>();
      return drained;
    }
  }

  public static class CoreMetric extends MetricCollector.Metric {
    private static final String NAMESPACE = "tracers";

    public CoreMetric(String name, long value, List<String> tags) {
      super(NAMESPACE, true, name, value, tags);
    }
  }
}
