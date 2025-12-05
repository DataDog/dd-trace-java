package datadog.opentelemetry.shim.trace;

import datadog.trace.util.Strings;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public final class OtelTracerProvider implements TracerProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelTracerProvider.class);
  private static final String DEFAULT_TRACER_NAME = "";

  public static final TracerProvider INSTANCE = new OtelTracerProvider();

  /** Tracer shims, indexed by instrumentation scope name. */
  private final Map<String, OtelTracer> tracers = new ConcurrentHashMap<>();

  @Override
  public Tracer get(String instrumentationScopeName) {
    return getTracerShim(instrumentationScopeName);
  }

  @Override
  public Tracer get(
      String instrumentationScopeName,
      @SuppressWarnings("unused") String instrumentationScopeVersion) {
    return getTracerShim(instrumentationScopeName);
  }

  @Override
  public TracerBuilder tracerBuilder(String instrumentationScopeName) {
    return new OtelTracerBuilder(this, instrumentationScopeName);
  }

  OtelTracer getTracerShim(String instrumentationScopeName) {
    if (Strings.isBlank(instrumentationScopeName)) {
      LOGGER.debug("Tracer requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_TRACER_NAME;
    }
    return tracers.computeIfAbsent(instrumentationScopeName, OtelTracer::new);
  }
}
