package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.executor.ThreadPoolExecutorInstrumentation.TPE;
import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.instrumentation.java.concurrent.executor.JavaExecutorInstrumentation;
import datadog.trace.instrumentation.java.concurrent.executor.NonStandardExecutorInstrumentation;
import datadog.trace.instrumentation.java.concurrent.executor.RejectedExecutionHandlerInstrumentation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RunnableFuture;

@AutoService(InstrumenterModule.class)
public class ExecutorModule extends InstrumenterModule.Tracing {
  public ExecutorModule() {
    super(EXECUTOR_INSTRUMENTATION_NAME);
  }

  @Override
  public Map<String, String> contextStore() {
    final Map<String, String> contextStore = new HashMap<>();
    contextStore.put(Runnable.class.getName(), State.class.getName());
    contextStore.put(TPE, Boolean.class.getName());
    contextStore.put(RunnableFuture.class.getName(), State.class.getName());
    return contextStore;
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> instrumenters = new ArrayList<>(5);
    final InstrumenterConfig config = InstrumenterConfig.get();
    instrumenters.add(new AsyncPropagatingDisableInstrumentation());
    if (InstrumenterConfig.get().isIntegrationEnabled(singleton("new-task-for"), true)) {
      instrumenters.add(new WrapRunnableAsNewTaskInstrumentation());
    }
    instrumenters.add(new JavaExecutorInstrumentation());
    if (config.isIntegrationEnabled(singleton(EXECUTOR_INSTRUMENTATION_NAME + ".other"), true)) {
      instrumenters.add(new NonStandardExecutorInstrumentation());
    }
    if (config.isIntegrationEnabled(singleton("rejected-execution-handler"), true)) {
      instrumenters.add(new RejectedExecutionHandlerInstrumentation());
    }

    return instrumenters;
  }
}
