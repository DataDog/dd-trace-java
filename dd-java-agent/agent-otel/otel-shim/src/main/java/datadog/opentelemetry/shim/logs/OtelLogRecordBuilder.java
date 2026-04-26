package datadog.opentelemetry.shim.logs;

import static datadog.opentelemetry.shim.trace.OtelExtractedContext.extract;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.otel.logs.data.OtelLogRecordProcessor;
import datadog.trace.bootstrap.otlp.logs.OtlpLogRecord;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelLogRecordBuilder implements LogRecordBuilder {
  // package-visible for testing
  static TimeSource TIME_SOURCE = SystemTimeSource.INSTANCE;

  private static final AttributeKey<String> EXCEPTION_TYPE_KEY = stringKey("exception.type");
  private static final AttributeKey<String> EXCEPTION_MESSAGE_KEY = stringKey("exception.message");

  private final OtelLogger logger;

  private long timestampNanos;
  private long observedNanos;
  private Severity severity = Severity.UNDEFINED_SEVERITY_NUMBER;
  @Nullable private String severityText;
  private int bodyType;
  @Nullable private Object bodyValue;
  @Nullable private String eventName;
  @Nullable private Map<AttributeKey<?>, Object> attributes;
  @Nullable private Context context;

  OtelLogRecordBuilder(OtelLogger logger) {
    this.logger = logger;
  }

  @Override
  public LogRecordBuilder setTimestamp(long timestamp, TimeUnit unit) {
    this.timestampNanos = unit.toNanos(timestamp);
    return this;
  }

  @Override
  public LogRecordBuilder setTimestamp(Instant instant) {
    this.timestampNanos = TimeUnit.SECONDS.toNanos(instant.getEpochSecond()) + instant.getNano();
    return this;
  }

  @Override
  public LogRecordBuilder setObservedTimestamp(long timestamp, TimeUnit unit) {
    this.observedNanos = unit.toNanos(timestamp);
    return this;
  }

  @Override
  public LogRecordBuilder setObservedTimestamp(Instant instant) {
    this.observedNanos = TimeUnit.SECONDS.toNanos(instant.getEpochSecond()) + instant.getNano();
    return this;
  }

  @Override
  public LogRecordBuilder setSeverity(Severity severity) {
    this.severity = severity;
    return this;
  }

  @Override
  public LogRecordBuilder setSeverityText(String severityText) {
    this.severityText = severityText;
    return this;
  }

  @Override
  public LogRecordBuilder setBody(String value) {
    this.bodyType = 1;
    this.bodyValue = value;
    return this;
  }

  @Override
  public LogRecordBuilder setBody(Value<?> body) {
    this.bodyType = body.getType().ordinal();
    this.bodyValue = body.getValue();
    return this;
  }

  @Override
  public <T> LogRecordBuilder setAttribute(@Nullable AttributeKey<T> key, @Nullable T value) {
    if (key == null || key.getKey().isEmpty()) {
      return this;
    }
    if (value != null) {
      if (attributes == null) {
        attributes = new HashMap<>();
      }
      attributes.put(key, value);
    } else if (attributes != null) {
      attributes.remove(key);
    }
    return this;
  }

  @Override
  public LogRecordBuilder setContext(Context context) {
    this.context = context;
    return this;
  }

  public LogRecordBuilder setEventName(String eventName) {
    this.eventName = eventName;
    return this;
  }

  public LogRecordBuilder setException(@Nullable Throwable throwable) {
    if (throwable != null) {
      setExceptionAttribute(EXCEPTION_TYPE_KEY, throwable.getClass().getName());
      setExceptionAttribute(EXCEPTION_MESSAGE_KEY, throwable.getMessage());
    }
    return this;
  }

  private void setExceptionAttribute(AttributeKey<String> key, @Nullable String value) {
    // avoid overwriting/removing existing exception details
    if (value != null && (attributes == null || !attributes.containsKey(key))) {
      setAttribute(key, value);
    }
  }

  @Override
  public void emit() {
    Context context = this.context != null ? this.context : Context.current();
    if (logger.isEnabled(severity, context)) {
      OtelLogRecordProcessor.INSTANCE.addLog(
          new OtlpLogRecord(
              logger.instrumentationScope,
              timestampNanos,
              observedNanos != 0 ? observedNanos : TIME_SOURCE.getCurrentTimeNanos(),
              severity.getSeverityNumber(),
              severityText,
              bodyType,
              bodyValue,
              eventName,
              attributes != null ? new HashMap<>(attributes) : null,
              extract(context)));
    }
  }
}
