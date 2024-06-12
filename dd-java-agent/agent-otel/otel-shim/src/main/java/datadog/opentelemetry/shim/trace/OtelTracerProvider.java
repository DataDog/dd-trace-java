package datadog.opentelemetry.shim.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ParametersAreNonnullByDefault
public class OtelTracerProvider implements TracerProvider {
  private static final Logger LOGGER = LoggerFactory.getLogger(OtelTracerProvider.class);
  private static final String DEFAULT_TRACER_NAME = "";
  public static final TracerProvider INSTANCE = new OtelTracerProvider();

  /** Tracer instances, indexed by instrumentation scope name. */
  private final Map<String, Tracer> tracers;

  public OtelTracerProvider() {
    this.tracers = new HashMap<>();
  }

  @Override
  public Tracer get(String instrumentationScopeName) {
    Tracer tracer = this.tracers.get(instrumentationScopeName);
    if (tracer == null) {
      tracer = tracerBuilder(instrumentationScopeName).build();
      this.tracers.put(instrumentationScopeName, tracer);
    }
    return tracer;
  }

  @Override
  public Tracer get(String instrumentationScopeName, String instrumentationScopeVersion) {
    Tracer tracer = this.tracers.get(instrumentationScopeName);
    if (tracer == null) {
      tracer =
          tracerBuilder(instrumentationScopeName)
              .setInstrumentationVersion(instrumentationScopeVersion)
              .build();
      this.tracers.put(instrumentationScopeName, tracer);
    }
    return tracer;
  }

  @Override
  public TracerBuilder tracerBuilder(String instrumentationScopeName) {
    if (instrumentationScopeName.trim().isEmpty()) {
      LOGGER.debug("Tracer requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_TRACER_NAME;
    }
    return new OtelTracerBuilder(instrumentationScopeName);
  }
}
