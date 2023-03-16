package datadog.telemetry.metric;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;

import java.util.Collection;

public class MetricPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {
    @Override
    public void doIteration(TelemetryService service) {
        Collection<Metric> metrics = MetricCollector.get().drain();

        for (Metric metric: metrics) {
            service.addMetric(metric);
        }
    }
}
