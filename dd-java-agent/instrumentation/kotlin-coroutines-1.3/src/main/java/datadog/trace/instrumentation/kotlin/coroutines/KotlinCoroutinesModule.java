package datadog.trace.instrumentation.kotlin.coroutines;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Arrays;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class KotlinCoroutinesModule extends InstrumenterModule.ContextTracking {
  public KotlinCoroutinesModule() {
    super("kotlin_coroutine");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DatadogThreadContextElement", packageName + ".DatadogThreadContextElement$1"
    };
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new CoroutineContextInstrumentation(),
        new CoroutineInstrumentation(),
        new LazyCoroutineInstrumentation());
  }
}
