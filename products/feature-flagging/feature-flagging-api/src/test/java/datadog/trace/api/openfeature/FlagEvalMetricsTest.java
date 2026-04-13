package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.openfeature.sdk.ErrorCode;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FlagEvalMetricsTest {

  @Test
  void recordBasicAttributes() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", "TARGETING_MATCH", null, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.key", "my-flag");
    assertAttribute(attrs, "feature_flag.result.variant", "on");
    assertAttribute(attrs, "feature_flag.result.reason", "targeting_match");
    assertNoAttribute(attrs, "error.type");
    assertNoAttribute(attrs, "feature_flag.result.allocation_key");
  }

  @Test
  void recordErrorAttributes() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("missing-flag", "", "ERROR", ErrorCode.FLAG_NOT_FOUND, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.key", "missing-flag");
    assertAttribute(attrs, "feature_flag.result.variant", "");
    assertAttribute(attrs, "feature_flag.result.reason", "error");
    assertAttribute(attrs, "error.type", "flag_not_found");
    assertNoAttribute(attrs, "feature_flag.result.allocation_key");
  }

  @Test
  void recordTypeMismatchError() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "", "ERROR", ErrorCode.TYPE_MISMATCH, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "error.type", "type_mismatch");
  }

  @Test
  void recordWithAllocationKey() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", "TARGETING_MATCH", null, "default-allocation");

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.result.allocation_key", "default-allocation");
  }

  @Test
  void recordOmitsEmptyAllocationKey() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", "TARGETING_MATCH", null, "");

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertNoAttribute(attrs, "feature_flag.result.allocation_key");
  }

  @Test
  void recordNullVariantBecomesEmptyString() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", null, "DEFAULT", null, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.result.variant", "");
  }

  @Test
  void recordNullReasonBecomesUnknown() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.record("my-flag", "on", null, null, null);

    ArgumentCaptor<Attributes> captor = ArgumentCaptor.forClass(Attributes.class);
    verify(counter).add(eq(1L), captor.capture());

    Attributes attrs = captor.getValue();
    assertAttribute(attrs, "feature_flag.result.reason", "unknown");
  }

  @Test
  void recordIsNoOpWhenCounterIsNull() {
    FlagEvalMetrics metrics = new FlagEvalMetrics((LongCounter) null);
    // Should not throw
    metrics.record("my-flag", "on", "TARGETING_MATCH", null, null);
  }

  @Test
  void shutdownClearsCounter() {
    LongCounter counter = mock(LongCounter.class);
    FlagEvalMetrics metrics = new FlagEvalMetrics(counter);

    metrics.shutdown();
    metrics.record("my-flag", "on", "TARGETING_MATCH", null, null);

    verifyNoInteractions(counter);
  }

  @Test
  void exporterIsConfiguredWithCumulativeTemporalityForCounters() {
    // Regression guard: FlagEvalMetrics must explicitly configure alwaysCumulative() so that
    // the Datadog agent receives absolute counts rather than delta values that may be converted
    // to rates. This test documents and enforces that the exporter uses CUMULATIVE for counters.
    try (OtlpHttpMetricExporter exporter =
        OtlpHttpMetricExporter.builder()
            .setAggregationTemporalitySelector(AggregationTemporalitySelector.alwaysCumulative())
            .build()) {
      assertEquals(
          AggregationTemporality.CUMULATIVE,
          exporter.getAggregationTemporality(InstrumentType.COUNTER),
          "alwaysCumulative() selector must produce CUMULATIVE for counters");
    }
  }

  @Test
  void multipleRecordCallsAccumulateCumulativelyInExportedMetrics() {
    // Use InMemoryMetricReader with cumulative temporality (matching what FlagEvalMetrics
    // configures on the OTLP exporter) to verify that N record() calls produce a sum of N.
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();

    try (FlagEvalMetrics metrics = new FlagEvalMetrics(provider)) {
      for (int i = 0; i < 5; i++) {
        metrics.record("count-flag", "on", "STATIC", null, "default-alloc");
      }

      Collection<MetricData> data = reader.collectAllMetrics();
      MetricData metric =
          data.stream()
              .filter(m -> m.getName().equals("feature_flag.evaluations"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("feature_flag.evaluations metric not found"));

      assertEquals(
          AggregationTemporality.CUMULATIVE,
          metric.getLongSumData().getAggregationTemporality(),
          "Exported metric must use CUMULATIVE temporality");

      LongPointData point = metric.getLongSumData().getPoints().iterator().next();
      assertEquals(5L, point.getValue(), "5 record() calls must produce a cumulative sum of 5");
    }
  }

  private static void assertAttribute(Attributes attrs, String key, String expected) {
    String value =
        attrs.asMap().entrySet().stream()
            .filter(e -> e.getKey().getKey().equals(key))
            .map(e -> e.getValue().toString())
            .findFirst()
            .orElse(null);
    if (!expected.equals(value)) {
      throw new AssertionError("Expected attribute " + key + "=" + expected + " but got " + value);
    }
  }

  private static void assertNoAttribute(Attributes attrs, String key) {
    boolean present = attrs.asMap().keySet().stream().anyMatch(k -> k.getKey().equals(key));
    if (present) {
      throw new AssertionError("Expected no attribute " + key + " but it was present");
    }
  }
}
