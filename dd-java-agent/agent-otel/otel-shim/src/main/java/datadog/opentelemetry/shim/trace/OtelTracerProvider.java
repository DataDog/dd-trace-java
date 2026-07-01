package datadog.opentelemetry.shim.trace;

import datadog.trace.bootstrap.otel.common.OtelInstrumentationScope;
import datadog.trace.util.Strings;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public final class OtelTracerProvider implements TracerProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelTracerProvider.class);
  private static final String DEFAULT_TRACER_NAME = "";

  public static final TracerProvider INSTANCE = new OtelTracerProvider();

  /** Tracer shims, indexed by instrumentation scope. */
  private final Map<OtelInstrumentationScope, OtelTracer> tracers = new ConcurrentHashMap<>();

  @Override
  public Tracer get(String instrumentationScopeName) {
    return getTracerShim(instrumentationScopeName, null, null);
  }

  @Override
  public Tracer get(String instrumentationScopeName, String instrumentationScopeVersion) {
    return getTracerShim(instrumentationScopeName, instrumentationScopeVersion, null);
  }

  @Override
  public TracerBuilder tracerBuilder(String instrumentationScopeName) {
    return new OtelTracerBuilder(this, instrumentationScopeName);
  }

  OtelTracer getTracerShim(
      String instrumentationScopeName,
      @Nullable String instrumentationScopeVersion,
      @Nullable String schemaUrl) {
    if (Strings.isBlank(instrumentationScopeName)) {
      LOGGER.debug("Tracer requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_TRACER_NAME;
    }
    return tracers.computeIfAbsent(
        new OtelInstrumentationScope(
            instrumentationScopeName, instrumentationScopeVersion, schemaUrl),
        OtelTracer::new);
  }
}
