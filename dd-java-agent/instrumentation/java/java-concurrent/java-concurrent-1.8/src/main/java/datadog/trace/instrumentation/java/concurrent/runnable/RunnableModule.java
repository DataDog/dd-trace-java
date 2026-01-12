package datadog.trace.instrumentation.java.concurrent.runnable;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class RunnableModule extends InstrumenterModule.Tracing {
  final boolean runnableEnabled;
  final boolean consumerTaskEnabled;
  final boolean runnableFutureEnabled;

  public RunnableModule() {
    super(EXECUTOR_INSTRUMENTATION_NAME);
    final InstrumenterConfig config = InstrumenterConfig.get();
    runnableEnabled = config.isIntegrationEnabled(singleton("runnable"), true);
    consumerTaskEnabled = config.isIntegrationEnabled(singleton("consumer-task"), true);
    runnableFutureEnabled = config.isIntegrationEnabled(singleton("runnable-future"), true);
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> ret = new HashMap<>();
    if (runnableEnabled) {
      ret.put(Runnable.class.getName(), State.class.getName());
    }
    if (consumerTaskEnabled) {
      ret.put("java.util.concurrent.ForkJoinTask", State.class.getName());
    }
    if (runnableFutureEnabled) {
      ret.put("java.util.concurrent.RunnableFuture", State.class.getName());
    }
    return ret;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> ret = new ArrayList<>(3);
    if (consumerTaskEnabled) {
      ret.add(new ConsumerTaskInstrumentation());
    }
    if (runnableFutureEnabled) {
      ret.add(new RunnableFutureInstrumentation());
    }
    if (runnableEnabled) {
      ret.add(new RunnableInstrumentation());
    }
    return ret;
  }
}
