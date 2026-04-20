package datadog.trace.instrumentation.cics;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class CicsModule extends InstrumenterModule.Tracing {
  public CicsModule() {
    super("cics");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CicsDecorator"};
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new ECIInteractionInstrumentation(), new JavaGatewayInterfaceInstrumentation());
  }
}
