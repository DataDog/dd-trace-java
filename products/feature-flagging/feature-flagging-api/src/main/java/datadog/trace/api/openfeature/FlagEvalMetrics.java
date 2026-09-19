package datadog.trace.api.openfeature;

import dev.openfeature.sdk.ErrorCode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.io.Closeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class FlagEvalMetrics implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(FlagEvalMetrics.class);

  private static final String METER_NAME = "ddtrace.openfeature";
  private static final String METRIC_NAME = "feature_flag.evaluations";
  private static final String METRIC_UNIT = "{evaluation}";
  private static final String METRIC_DESC = "Number of feature flag evaluations";

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
  private volatile OpenTelemetry telemetry;
  private volatile boolean closed;
  private final boolean suppliedCounter;

  FlagEvalMetrics() {
    suppliedCounter = false;
  }

  private LongCounter resolveCounter() {
    if (closed || suppliedCounter) {
      return counter;
    }
    try {
      final OpenTelemetry current = GlobalOpenTelemetry.getOrNoop();
      if (current == telemetry) {
        return counter;
      }
      return initializeCounter(current);
    } catch (LinkageError e) {
      log.debug("OpenTelemetry API 1.57 or newer is required for evaluation metrics", e);
      shutdown();
    } catch (Exception e) {
      log.error("Failed to initialize flag evaluation metrics", e);
    }
    return null;
  }

  private synchronized LongCounter initializeCounter(final OpenTelemetry current) {
    if (closed) {
      return null;
    }
    if (current != telemetry) {
      Meter meter = current.getMeterProvider().meterBuilder(METER_NAME).build();
      counter =
          meter
              .counterBuilder(METRIC_NAME)
              .setUnit(METRIC_UNIT)
              .setDescription(METRIC_DESC)
              .build();
      telemetry = current;

      log.debug("Flag evaluation metrics initialized");
    }
    return counter;
  }

  /** Package-private constructor for testing with a mock counter. */
  FlagEvalMetrics(LongCounter counter) {
    this.suppliedCounter = true;
    this.counter = counter;
  }

  void record(
      String flagKey, String variant, String reason, ErrorCode errorCode, String allocationKey) {
    LongCounter c = resolveCounter();
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

  synchronized void shutdown() {
    closed = true;
    counter = null;
    telemetry = null;
  }
}
