package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.executor.ThreadPoolExecutorInstrumentation.TPE;
import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AutoService(InstrumenterModule.class)
public class ExecutorModule extends InstrumenterModule.ContextTracking
    implements ExcludeFilterProvider {
  private static final Logger log = LoggerFactory.getLogger(ExecutorModule.class);

  public ExecutorModule() {
    super(EXECUTOR_INSTRUMENTATION_NAME);
    if (InstrumenterConfig.get().isTraceExecutorsAll()) {
      log.warn("Tracing all executors enabled. This is not a recommended setting.");
    }
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>(4);
    contextStore.put("java.util.concurrent.RunnableFuture", State.class.getName());
    // TODO get rid of this
    contextStore.put("java.lang.Runnable", State.class.getName());
    contextStore.put(TPE, Boolean.class.getName());
    return Collections.unmodifiableMap(contextStore);
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    Map<ExcludeFilter.ExcludeType, List<String>> map = new HashMap<>();
    map.put(
        RUNNABLE,
        Arrays.asList(
            "datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper",
            "datadog.trace.bootstrap.instrumentation.java.concurrent.ComparableRunnable"));
    map.put(
        EXECUTOR,
        Collections.singletonList("org.apache.mina.filter.executor.OrderedThreadPoolExecutor"));

    return Collections.unmodifiableMap(map);
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> instrumenters = new ArrayList<>();
    instrumenters.add(new JavaExecutorInstrumentation());
    if (InstrumenterConfig.get()
        .isIntegrationEnabled(singleton(EXECUTOR_INSTRUMENTATION_NAME + ".other"), true)) {
      instrumenters.add(new NonStandardExecutorInstrumentation());
    }
    if (InstrumenterConfig.get()
        .isIntegrationEnabled(singleton("rejected-execution-handler"), true)) {
      instrumenters.add(new RejectedExecutionHandlerInstrumentation());
    }
    instrumenters.add(new ThreadPoolExecutorInstrumentation());
    return instrumenters;
  }
}
