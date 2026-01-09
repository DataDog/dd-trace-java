package datadog.trace.instrumentation.datanucleus;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class DatanucleusModule extends InstrumenterModule.Tracing {
  public DatanucleusModule() {
    super("datanucleus");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatanucleusDecorator",
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new ExecutionContextInstrumentation(),
        new JDOQueryInstrumentation(),
        new JDOTransactionInstrumentation());
  }
}
