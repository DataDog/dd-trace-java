package datadog.trace.instrumentation.reactorcore;

import static java.util.Arrays.asList;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class ReactorCoreModule extends InstrumenterModule.ContextTracking {
  public ReactorCoreModule() {
    super("reactor-core");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TracingCoreSubscriber", packageName + ".ReactorAsyncResultExtension",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> store = new HashMap<>();
    store.put("reactor.core.publisher.Mono", Context.class.getName());
    store.put("reactor.core.publisher.Flux", Context.class.getName());
    return store;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new MonoInstrumentation(), new FluxInstrumentation());
  }
}
