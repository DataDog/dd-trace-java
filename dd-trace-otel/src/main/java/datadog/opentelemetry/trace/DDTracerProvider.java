package datadog.opentelemetry.trace;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class DDTracerProvider implements TracerProvider {
  private static final Logger logger = Logger.getLogger(DDTracerProvider.class.getName());
  private static final String DEFAULT_TRACER_NAME = "";
  private static final Tracer NOOP_TRACER = spanName -> null;

  /** Tracer instances, indexed by instrumentation scope name. */
  private final Map<String, Tracer> tracers;

  public DDTracerProvider() {
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
      logger.fine("Tracer requested without instrumentation scope name.");
      instrumentationScopeName = DEFAULT_TRACER_NAME;
    }
    return new DDTracerBuilder(instrumentationScopeName);
  }
}
