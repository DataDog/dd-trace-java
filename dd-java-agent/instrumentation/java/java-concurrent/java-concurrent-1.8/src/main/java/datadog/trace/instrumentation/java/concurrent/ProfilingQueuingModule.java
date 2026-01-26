package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.FORK_JOIN_POOL_INSTRUMENTATION_NAME;
import static java.util.Collections.singleton;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.instrumentation.java.concurrent.forkjoin.JavaForkJoinWorkQueueInstrumentation;
import java.util.ArrayList;
import java.util.List;

@AutoService(InstrumenterModule.class)
public class ProfilingQueuingModule extends InstrumenterModule.Profiling {
  public ProfilingQueuingModule() {
    super(EXECUTOR_INSTRUMENTATION_NAME);
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && ConfigProvider.getInstance()
            .getBoolean(
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED,
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    final List<Instrumenter> instrumenters = new ArrayList<>(2);
    final InstrumenterConfig config = InstrumenterConfig.get();
    if (config.isIntegrationEnabled(singleton(FORK_JOIN_POOL_INSTRUMENTATION_NAME), true)) {
      instrumenters.add(new JavaForkJoinWorkQueueInstrumentation());
    }
    if (config.isIntegrationEnabled(singleton("task-unwrapping"), true)) {
      instrumenters.add(new TaskUnwrappingInstrumentation());
    }
    return instrumenters;
  }
}
