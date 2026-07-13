package datadog.trace.instrumentation.sparkjava;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public final class SparkJavaModule extends InstrumenterModule.Tracing {

  public SparkJavaModule() {
    super("sparkjava", "sparkjava-2.3");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SparkJavaDecorator",
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new MatcherFilterInstrumentation(),
        new JettyHandlerInstrumentation(),
        new RouteHandlerInstrumentation());
  }
}
