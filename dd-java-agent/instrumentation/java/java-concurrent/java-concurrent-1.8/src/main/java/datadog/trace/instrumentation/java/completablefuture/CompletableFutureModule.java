package datadog.trace.instrumentation.java.completablefuture;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ConcurrentState;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Groups the instrumentations for CompletableFuture and related classes. */
@AutoService(InstrumenterModule.class)
public final class CompletableFutureModule extends InstrumenterModule.Tracing {

  public CompletableFutureModule() {
    super("java_completablefuture", "java_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> stores = new HashMap<>();
    stores.put("java.util.concurrent.ForkJoinTask", State.class.getName());
    stores.put(
        "java.util.concurrent.CompletableFuture$UniCompletion", ConcurrentState.class.getName());
    return stores;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new AsyncTaskInstrumentation(),
        new CompletableFutureUniCompletionInstrumentation(),
        new CompletableFutureUniCompletionSubclassInstrumentation());
  }
}
