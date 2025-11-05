package datadog.trace.instrumentation.grizzly.client;

import static java.util.Collections.singletonList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.Collections;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class GrizzlyClientModule extends InstrumenterModule.Tracing {
  public GrizzlyClientModule() {
    super("grizzly-client", "ning");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isIntegrationEnabled(Collections.singleton("mule"), false);
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ClientDecorator",
      packageName + ".InjectAdapter",
      packageName + ".AsyncHandlerAdapter",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return singletonList(new AsyncHttpClientInstrumentation());
  }
}
