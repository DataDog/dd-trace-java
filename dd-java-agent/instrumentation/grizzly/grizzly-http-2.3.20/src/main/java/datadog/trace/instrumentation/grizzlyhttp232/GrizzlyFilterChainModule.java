package datadog.trace.instrumentation.grizzlyhttp232;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class GrizzlyFilterChainModule extends InstrumenterModule.Tracing {
  public GrizzlyFilterChainModule() {
    super("grizzly-filterchain");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(Collections.singleton("mule"), false);
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new DefaultFilterChainInstrumentation(),
        new FilterInstrumentation(),
        new HttpCodecFilterInstrumentation(),
        new HttpServerFilterInstrumentation());
  }
}
