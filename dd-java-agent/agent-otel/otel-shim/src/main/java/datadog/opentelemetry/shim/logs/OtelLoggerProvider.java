package datadog.opentelemetry.shim.logs;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.bootstrap.otel.logs.data.OtelLogRecordProcessor;
import datadog.trace.util.Strings;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public final class OtelLoggerProvider implements LoggerProvider {
  private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(OtelLoggerProvider.class);
  private static final String DEFAULT_LOGGER_NAME = "unknown";

  public static final LoggerProvider INSTANCE = new OtelLoggerProvider();

  /** Logger shims, indexed by instrumentation scope. */
  private final Map<OtelInstrumentationScope, OtelLogger> loggers = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  private OtelLoggerProvider() {
    // register attribute reader for class-loader where this provider is being used/injected
    OtelLogRecordProcessor.registerAttributeReader(
        AttributeKey.class.getClassLoader(),
        (attributes, visitor) ->
            ((Map<AttributeKey<?>, ?>) attributes)
                .forEach((a, v) -> visitor.visitAttribute(a.getType().ordinal(), a.getKey(), v)));
  }

  @Override
  public Logger get(String instrumentationScopeName) {
    return getLoggerShim(instrumentationScopeName, null, null);
  }

  @Override
  public LoggerBuilder loggerBuilder(String instrumentationScopeName) {
    return new OtelLoggerBuilder(this, instrumentationScopeName);
  }

  Logger getLoggerShim(
      String instrumentationScopeName,
      @Nullable String instrumentationScopeVersion,
      @Nullable String schemaUrl) {
    if (Strings.isBlank(instrumentationScopeName)) {
      LOGGER.debug("Logger requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_LOGGER_NAME;
    }
    return loggers.computeIfAbsent(
        new OtelInstrumentationScope(
            instrumentationScopeName, instrumentationScopeVersion, schemaUrl),
        OtelLogger::new);
  }
}
