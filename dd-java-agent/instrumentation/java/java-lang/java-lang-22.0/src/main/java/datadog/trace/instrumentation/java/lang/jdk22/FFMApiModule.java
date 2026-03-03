package datadog.trace.instrumentation.java.lang.jdk22;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Pair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class FFMApiModule extends InstrumenterModule.Tracing {
  public FFMApiModule() {
    super("java-lang-22");
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    ret.put("java.lang.foreign.SymbolLookup", String.class.getName());
    ret.put("java.lang.foreign.MemorySegment", Pair.class.getName());
    return ret;
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && !InstrumenterConfig.get().getTraceNativeMethods().isEmpty();
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(
        new LinkerInstrumentation(),
        new SymbolLookupInstrumentation(),
        new SymbolLookupStaticInstrumentation());
  }
}
