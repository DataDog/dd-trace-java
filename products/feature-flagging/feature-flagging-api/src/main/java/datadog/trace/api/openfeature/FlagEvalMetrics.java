package datadog.trace.api.openfeature;

import datadog.trace.config.inversion.ConfigHelper;
import dev.openfeature.sdk.ErrorCode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import java.io.Closeable;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FlagEvalMetrics implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(FlagEvalMetrics.class);

  private static final String METER_NAME = "ddtrace.openfeature";
  private static final String METRIC_NAME = "feature_flag.evaluations";
  private static final String METRIC_UNIT = "{evaluation}";
  private static final String METRIC_DESC = "Number of feature flag evaluations";
  private static final Duration EXPORT_INTERVAL = Duration.ofSeconds(10);

  private static final String DEFAULT_ENDPOINT = "http://localhost:4318/v1/metrics";
  // Signal-specific env var (used as-is, must include /v1/metrics path)
  private static final String ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT";
  // Generic env var fallback (base URL, /v1/metrics is appended)
  private static final String ENDPOINT_GENERIC_ENV = "OTEL_EXPORTER_OTLP_ENDPOINT";

  private static final AttributeKey<String> ATTR_FLAG_KEY =
      AttributeKey.stringKey("feature_flag.key");
  private static final AttributeKey<String> ATTR_VARIANT =
      AttributeKey.stringKey("feature_flag.result.variant");
  private static final AttributeKey<String> ATTR_REASON =
      AttributeKey.stringKey("feature_flag.result.reason");
  private static final AttributeKey<String> ATTR_ERROR_TYPE = AttributeKey.stringKey("error.type");
  private static final AttributeKey<String> ATTR_ALLOCATION_KEY =
      AttributeKey.stringKey("feature_flag.result.allocation_key");

  private volatile LongCounter counter;
  // Typed as Closeable to avoid loading SdkMeterProvider at class-load time
  // when the OTel SDK is absent from the classpath
  private volatile Closeable meterProvider;

  FlagEvalMetrics() {
    try {
      String endpoint = ConfigHelper.env(ENDPOINT_ENV);
      if (endpoint == null || endpoint.isEmpty()) {
        String base = ConfigHelper.env(ENDPOINT_GENERIC_ENV);
        if (base != null && !base.isEmpty()) {
          endpoint = base.endsWith("/") ? base + "v1/metrics" : base + "/v1/metrics";
        } else {
          endpoint = DEFAULT_ENDPOINT;
        }
      }

      OtlpHttpMetricExporter exporter =
          OtlpHttpMetricExporter.builder()
              .setEndpoint(endpoint)
              .setAggregationTemporalitySelector(AggregationTemporalitySelector.alwaysCumulative())
              .build();

      PeriodicMetricReader reader =
          PeriodicMetricReader.builder(exporter).setInterval(EXPORT_INTERVAL).build();

      SdkMeterProvider sdkMeterProvider =
          SdkMeterProvider.builder().registerMetricReader(reader).build();
      meterProvider = sdkMeterProvider;

      Meter meter = sdkMeterProvider.meterBuilder(METER_NAME).build();
      counter =
          meter
              .counterBuilder(METRIC_NAME)
              .setUnit(METRIC_UNIT)
              .setDescription(METRIC_DESC)
              .build();

      log.debug("Flag evaluation metrics initialized, exporting to {}", endpoint);
    } catch (NoClassDefFoundError e) {
      log.error(
          "Evaluation logging is enabled but OpenTelemetry SDK is not on the classpath. "
              + "Add opentelemetry-sdk-metrics and opentelemetry-exporter-otlp to your dependencies, "
              + "or disable evaluation logging via Provider.Options.evaluationLogging(false).",
          e);
      counter = null;
      meterProvider = null;
    } catch (Exception e) {
      log.error("Failed to initialize flag evaluation metrics", e);
      counter = null;
      meterProvider = null;
    }
  }

  /** Package-private constructor for testing with a mock counter. */
  FlagEvalMetrics(LongCounter counter) {
    this.counter = counter;
    this.meterProvider = null;
  }

  /** Package-private constructor for integration testing with an injected SdkMeterProvider. */
  FlagEvalMetrics(SdkMeterProvider sdkMeterProvider) {
    meterProvider = sdkMeterProvider;
    Meter meter = sdkMeterProvider.meterBuilder(METER_NAME).build();
    counter =
        meter.counterBuilder(METRIC_NAME).setUnit(METRIC_UNIT).setDescription(METRIC_DESC).build();
  }

  void record(
      String flagKey, String variant, String reason, ErrorCode errorCode, String allocationKey) {
    LongCounter c = counter;
    if (c == null) {
      return;
    }
    try {
      AttributesBuilder builder =
          Attributes.builder()
              .put(ATTR_FLAG_KEY, flagKey)
              .put(ATTR_VARIANT, variant != null ? variant : "")
              .put(ATTR_REASON, reason != null ? reason.toLowerCase() : "unknown");

      if (errorCode != null) {
        builder.put(ATTR_ERROR_TYPE, errorCode.name().toLowerCase());
      }

      if (allocationKey != null && !allocationKey.isEmpty()) {
        builder.put(ATTR_ALLOCATION_KEY, allocationKey);
      }

      c.add(1, builder.build());
    } catch (Exception e) {
      log.debug("Failed to record flag evaluation metric for {}", flagKey, e);
    }
  }

  @Override
  public void close() {
    shutdown();
  }

  void shutdown() {
    counter = null;
    Closeable mp = meterProvider;
    if (mp != null) {
      meterProvider = null;
      try {
        mp.close();
      } catch (Exception e) {
        // Ignore shutdown errors
      }
    }
  }
}
