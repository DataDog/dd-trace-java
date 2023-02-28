package datadog.telemetry.iast;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Metric;
import datadog.telemetry.api.Metric.TypeEnum;
import datadog.trace.api.iast.telemetry.IastMetric;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector.MetricData;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector.Point;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IastTelemetryPeriodicAction implements TelemetryRunnable.TelemetryPeriodicAction {

  private static final Logger LOGGER = LoggerFactory.getLogger(IastTelemetryPeriodicAction.class);

  static final String NAMESPACE = "iast";

  @Override
  public void doIteration(@Nonnull final TelemetryService service) {
    for (final MetricData data : IastTelemetryCollector.drain()) {
      final IastMetric iastMetric = data.getMetric();
      final List<Point> points = data.getPoints();
      final Metric metric = asTelemetryMetric(iastMetric).points(asTelemetryPoints(points));
      if (iastMetric.getTag() != null) {
        metric.addTagsItem(String.format("%s:%s", iastMetric.getTag(), data.getTag()));
      }
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Adding IAST metric {}", metric);
      }
      service.addMetric(metric);
    }
  }

  private Metric asTelemetryMetric(final IastMetric metric) {
    return new Metric()
        .namespace(NAMESPACE)
        .metric(metric.getName())
        .type(TypeEnum.valueOf(metric.getType()))
        .common(metric.isCommon());
  }

  private List<List<Number>> asTelemetryPoints(final List<Point> points) {
    return points.stream()
        .map(point -> Arrays.<Number>asList(point.getTimestamp(), point.getValue()))
        .collect(Collectors.toList());
  }
}
