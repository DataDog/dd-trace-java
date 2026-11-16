package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_OTEL_SEMANTICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_OTEL_SEMANTICS_ENABLED;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.List;
import java.util.Map;

/** Captures matched routes and selects their completed directive branches. */
@AutoService(InstrumenterModule.class)
public final class AkkaHttpRouteModule extends InstrumenterModule.Tracing {
  public AkkaHttpRouteModule() {
    super("akka-http-route");
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(TRACE_OTEL_SEMANTICS_ENABLED, DEFAULT_TRACE_OTEL_SEMANTICS_ENABLED);
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.http.scaladsl.server.PathMatcher", "java.lang.String");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new AkkaHttpPathMatcherInstrumentation(), new AkkaHttpDirectiveInstrumentation());
  }
}
