package datadog.trace.instrumentation.java.lang.jdk22;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class FFMApiModule extends InstrumenterModule.Tracing {
  public FFMApiModule() {
    super("ffm-native-tracing", "java-lang-22");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && !InstrumenterConfig.get().getTraceNativeMethods().isEmpty();
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("jdk.internal.loader.NativeLibrary", "java.lang.String");
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new LinkerInstrumentation(), new NativeLibraryInstrumentation());
  }
}
