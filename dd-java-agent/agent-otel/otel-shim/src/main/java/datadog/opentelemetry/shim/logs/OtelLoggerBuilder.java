package datadog.opentelemetry.shim.logs;

import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.LoggerBuilder;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
final class OtelLoggerBuilder implements LoggerBuilder {
  private final OtelLoggerProvider loggerProvider;

  private final String instrumentationScopeName;
  @Nullable private String instrumentationScopeVersion;
  @Nullable private String schemaUrl;

  OtelLoggerBuilder(OtelLoggerProvider loggerProvider, String instrumentationScopeName) {
    this.loggerProvider = loggerProvider;
    this.instrumentationScopeName = instrumentationScopeName;
  }

  @Override
  public LoggerBuilder setInstrumentationVersion(String instrumentationScopeVersion) {
    this.instrumentationScopeVersion = instrumentationScopeVersion;
    return this;
  }

  @Override
  public LoggerBuilder setSchemaUrl(String schemaUrl) {
    this.schemaUrl = schemaUrl;
    return this;
  }

  @Override
  public Logger build() {
    return loggerProvider.getLoggerShim(
        instrumentationScopeName, instrumentationScopeVersion, schemaUrl);
  }
}
