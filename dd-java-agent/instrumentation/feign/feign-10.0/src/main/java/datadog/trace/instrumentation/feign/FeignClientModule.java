package datadog.trace.instrumentation.feign;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class FeignClientModule extends InstrumenterModule.Tracing {

  public FeignClientModule() {
    super("feign");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".FeignClientDecorator",
      packageName + ".RequestInjectAdapter"
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new SyncClientInstrumentation(),
        new AsyncClientInstrumentation());
  }
}
