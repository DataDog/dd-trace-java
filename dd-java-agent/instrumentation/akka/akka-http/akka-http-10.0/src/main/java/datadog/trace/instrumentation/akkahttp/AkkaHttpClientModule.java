package datadog.trace.instrumentation.akkahttp;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class AkkaHttpClientModule extends InstrumenterModule.Tracing {
  public AkkaHttpClientModule() {
    super("akka-http", "akka-http-client");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AkkaHttpClientHelpers",
      packageName + ".AkkaHttpClientHelpers$OnCompleteHandler",
      packageName + ".AkkaHttpClientHelpers$AkkaHttpHeaders",
      packageName + ".AkkaHttpClientHelpers$HasSpanHeader",
      packageName + ".AkkaHttpClientDecorator",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new AkkaHttpSingleRequestInstrumentation(), new AkkaPoolMasterActorInstrumentation());
  }
}
