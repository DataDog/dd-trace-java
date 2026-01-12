package datadog.trace.instrumentation.java.concurrent.forkjoin;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.FORK_JOIN_POOL_INSTRUMENTATION_NAME;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Groups the instrumentations for ForkJoinPool, ForkJoinTask, and WorkQueue.
 */
@AutoService(InstrumenterModule.class)
public final class ForkJoinModule extends InstrumenterModule.Tracing {
  public ForkJoinModule() {
    super(EXECUTOR_INSTRUMENTATION_NAME, FORK_JOIN_POOL_INSTRUMENTATION_NAME);
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    List<Instrumenter> instrumenters = new ArrayList<>(3);
    instrumenters.add(new JavaForkJoinPoolInstrumentation());
    instrumenters.add(new JavaForkJoinTaskInstrumentation());
    // WorkQueue instrumentation is conditionally enabled based on profiling config
    if (ConfigProvider.getInstance()
        .getBoolean(
            ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED,
            ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT)) {
      instrumenters.add(new JavaForkJoinWorkQueueInstrumentation());
    }
    return instrumenters;
  }
}
