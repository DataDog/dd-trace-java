package datadog.trace.instrumentation.axis2;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class Axis2Module extends InstrumenterModule.Tracing {
  public Axis2Module() {
    super("axis2");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AxisMessageDecorator", packageName + ".TextMapInjectAdapter",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new AxisEngineInstrumentation(),
        new AxisTransportInstrumentation(),
        new WebSphereAsyncInstrumentation());
  }
}
