package datadog.trace.instrumentation.java.lang.jdk22;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.List;
import java.util.Map;
import java.util.Set;

@AutoService(InstrumenterModule.class)
public class FFMApiModule extends InstrumenterModule.Tracing {
  private final Map<String, Set<String>> tracedNativeMethods;
  public FFMApiModule() {
    super("java-lang-22");
    tracedNativeMethods = InstrumenterConfig.get().getTraceNativeMethods();
  }

  @Override
  public Map<String, String> contextStore() {
    return super.contextStore();
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && !tracedNativeMethods.isEmpty();
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return super.typeInstrumentations();
  }

  @Override
  public String[] helperClassNames() {
    return super.helperClassNames();
  }
}
