package datadog.trace.core.otlp.sdk;

import static datadog.trace.agent.test.utils.OtelMetricCollector.collect;
import static datadog.trace.bootstrap.otel.metrics.OtelInstrumentType.HISTOGRAM;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.agent.test.utils.OtelMetricCollector;
import datadog.trace.agent.test.utils.OtelMetricCollector.DataPointEntry;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.core.monitor.OtelComponentId;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OtelHealthMetricsCollectionTest {
  private static final String COLLECTION = "otel.sdk.metric_reader.collection.duration";
  private static final OtelHealthMetrics SINK = OtelHealthMetrics.INSTANCE;

  @BeforeAll
  static void registerHistogramFactory() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @Test
  void recordsSuccessfulCollectionDurationInSeconds() {
    OtelComponentId reader = OtelComponentId.next("otel_collection_test_success");

    SINK.onMetricCollection(reader, 250_000_000L, null);
    OtelMetricCollector collector = collect();

    assertEquals(HISTOGRAM, collector.descriptors.get(COLLECTION).getType());
    assertEquals("s", collector.descriptors.get(COLLECTION).getUnit().toString());
    assertEquals("datadog.trace.sdk", collector.scopes.get(COLLECTION));
    DataPointEntry point = onlyPoint(collector, reader);
    assertEquals("otel_collection_test_success", point.attrs.get("otel.component.type"));
    assertEquals(reader.name(), point.attrs.get("otel.component.name"));
    assertFalse(point.attrs.containsKey("error.type"));
    OtlpHistogramPoint histogram = (OtlpHistogramPoint) point.point;
    assertEquals(1.0, histogram.count);
    assertEquals(1, histogram.bucketCounts.size()); // empty bucket advice: a single bucket
    // no explicit bounds, so the encoder doesn't add an overflow bucket
    assertEquals(singletonList(Double.POSITIVE_INFINITY), histogram.bucketBoundaries);
    assertEquals(0.25, histogram.sum);
  }

  private static DataPointEntry onlyPoint(OtelMetricCollector collector, OtelComponentId reader) {
    List<DataPointEntry> points =
        collector.points(COLLECTION).stream()
            .filter(point -> reader.name().equals(point.attrs.get("otel.component.name")))
            .collect(toList());
    assertEquals(1, points.size());
    return points.get(0);
  }
}
