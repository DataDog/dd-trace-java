package datadog.trace.instrumentation.kotlin.coroutines;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.ArrayList;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class KotlinCoroutinesModule extends InstrumenterModule.ContextTracking {
  public KotlinCoroutinesModule() {
    super("kotlin_coroutine");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    List<Instrumenter> instrumenters = new ArrayList<>();
    instrumenters.add(new CoroutineContextInstrumentation());
    instrumenters.add(new CoroutineInstrumentation());
    instrumenters.add(new LazyCoroutineInstrumentation());
    if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
      instrumenters.add(new SuspensionInstrumentation());
    }
    return instrumenters;
  }
}
