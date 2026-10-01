package datadog.trace.instrumentation.r2dbc;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;
import java.util.Map;

/** Groups the R2DBC tracing and SQL comment injection instrumentations. */
@AutoService(InstrumenterModule.class)
public final class R2dbcModule extends InstrumenterModule.Tracing {

  public R2dbcModule() {
    super("r2dbc");
  }

  @Override
  public boolean isHelperClass(String className) {
    // The bundled r2dbc-proxy is relocated (binary shading, see build.gradle) into this
    // package rather than compiled from this module's own source, so it's never picked up by
    // module-output discovery — claim it explicitly instead.
    return className.startsWith(packageName + ".shaded.");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("io.r2dbc.spi.Connection", "io.r2dbc.spi.ConnectionFactoryOptions");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new R2dbcInstrumentation(), new R2dbcConnectionInstrumentation());
  }
}
