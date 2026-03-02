package datadog.trace.instrumentation.java.lang.jdk22;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
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
    ret.put("java.lang.foreign.SymbolLookup", "java.lang.String");
    ret.put("java.lang.foreign.MemorySegment", "java.lang.CharSequence");
    return ret;
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && !InstrumenterConfig.get().getTraceNativeMethods().isEmpty();
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new LinkerInstrumentation(), new SymbolLookupInstrumentation());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
        // this could be moved to the boostrap eventually
      "datadog.trace.instrumentation.trace_annotation.TraceDecorator",
    };
  }
}
