package com.datadog.openfeature;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.Reason;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.io.Closeable;
import java.util.concurrent.TimeUnit;
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

  // The attribute values of the standard reasons and error codes, lower-cased once.
  private static final String[] REASONS = new String[Reason.values().length];
  private static final String[] LOWER_CASE_REASONS = new String[REASONS.length];
  private static final String[] ERROR_TYPES = new String[ErrorCode.values().length];

  static {
    for (final Reason reason : Reason.values()) {
      REASONS[reason.ordinal()] = reason.name();
      LOWER_CASE_REASONS[reason.ordinal()] = reason.name().toLowerCase();
    }
    for (final ErrorCode errorCode : ErrorCode.values()) {
      ERROR_TYPES[errorCode.ordinal()] = errorCode.name().toLowerCase();
    }
  }

  /** How long the metrics wait for an SDK before looking for one again. */
  static final long NOOP_RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

  private volatile LongCounter counter;
  private volatile OpenTelemetry telemetry;
  private volatile boolean closed;
  private volatile Boolean legacyAgentBridge;
  private volatile boolean getOrNoopUnavailable;
  private final boolean suppliedCounter;
  private final long noopRecheckNanos;
  private volatile long lastNoopCheckNanos;

  FlagEvalMetrics() {
    this(NOOP_RECHECK_NANOS);
  }

  FlagEvalMetrics(final long noopRecheckNanos) {
    this.suppliedCounter = false;
    this.noopRecheckNanos = noopRecheckNanos;
    this.lastNoopCheckNanos = System.nanoTime() - noopRecheckNanos;
  }

  private LongCounter resolveCounter() {
    if (closed || suppliedCounter) {
      return counter;
    }
    // GlobalOpenTelemetry.getOrNoop() takes a global lock on every call, so it is not called per
    // evaluation. The global can be set only once, so the counter of a registered SDK stays valid.
    final LongCounter resolved = counter;
    if (resolved != null) {
      return resolved;
    }
    // Without an SDK, look again from time to time: one may still be registered.
    final long now = System.nanoTime();
    if (now - lastNoopCheckNanos < noopRecheckNanos) {
      return null;
    }
    lastNoopCheckNanos = now;
    try {
      final OpenTelemetry current = currentOpenTelemetry();
      if (current == telemetry) {
        return counter;
      }
      if (current == OpenTelemetry.noop()) {
        // Nothing to record into until the application registers an SDK: skip the attributes.
        return null;
      }
      return initializeCounter(current);
    } catch (LinkageError e) {
      log.debug("OpenTelemetry API is not available for evaluation metrics", e);
      shutdown();
    } catch (Exception e) {
      log.error("Failed to initialize flag evaluation metrics", e);
    }
    return null;
  }

  private OpenTelemetry currentOpenTelemetry() {
    if (!getOrNoopUnavailable) {
      try {
        OpenTelemetry current = GlobalOpenTelemetry.getOrNoop();
        if (current == OpenTelemetry.noop() && hasLegacyAgentBridge()) {
          // Older OTel agents bridge get(), but predate getOrNoop(). Only use get() when the
          // agent has injected its API bridge. Mere agent-JAR presence is not sufficient.
          current = GlobalOpenTelemetry.get();
        }
        return current;
      } catch (NoSuchMethodError e) {
        // getOrNoop() was added in OpenTelemetry API 1.57
        getOrNoopUnavailable = true;
      }
    }
    return GlobalOpenTelemetry.get();
  }

  private boolean hasLegacyAgentBridge() {
    Boolean available = legacyAgentBridge;
    if (available == null) {
      try {
        Class.forName(
            "io.opentelemetry.javaagent.instrumentation.opentelemetryapi.ApplicationOpenTelemetry",
            false,
            GlobalOpenTelemetry.class.getClassLoader());
        available = true;
      } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
        available = false;
      }
      legacyAgentBridge = available;
    }
    return available;
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
    this.noopRecheckNanos = 0;
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
              .put(ATTR_REASON, reason != null ? lowerCaseReason(reason) : "unknown");

      if (errorCode != null) {
        builder.put(ATTR_ERROR_TYPE, ERROR_TYPES[errorCode.ordinal()]);
      }

      if (allocationKey != null && !allocationKey.isEmpty()) {
        builder.put(ATTR_ALLOCATION_KEY, allocationKey);
      }

      c.add(1, builder.build());
    } catch (Exception e) {
      log.debug("Failed to record flag evaluation metric for {}", flagKey, e);
    }
  }

  private static String lowerCaseReason(final String reason) {
    for (int i = 0; i < REASONS.length; i++) {
      if (REASONS[i].equals(reason)) {
        return LOWER_CASE_REASONS[i];
      }
    }
    return reason.toLowerCase();
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
