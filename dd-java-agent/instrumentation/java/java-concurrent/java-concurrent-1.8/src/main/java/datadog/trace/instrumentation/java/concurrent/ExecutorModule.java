package datadog.trace.instrumentation.java;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.ArrayList;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class ExecutorModule extends InstrumenterModule.Tracing {
  public ExecutorModule() {
    super(EXECUTOR_INSTRUMENTATION_NAME);
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> instrumenters = new ArrayList<>();
    
    return instrumenters;
  }
}
