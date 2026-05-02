package datadog.trace.instrumentation.java.concurrent.forkjoin;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.FORK_JOIN_POOL_INSTRUMENTATION_NAME;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public class ForkJoinModule extends InstrumenterModule.ContextTracking
    implements ExcludeFilterProvider {

  public ForkJoinModule() {
    super(EXECUTOR_INSTRUMENTATION_NAME, FORK_JOIN_POOL_INSTRUMENTATION_NAME);
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        RUNNABLE_FUTURE,
        asList(
            "java.util.concurrent.ForkJoinTask$AdaptedCallable",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnable",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnableAction",
            "java.util.concurrent.ForkJoinTask$AdaptedInterruptibleCallable"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return asList(new JavaForkJoinPoolInstrumentation(), new JavaForkJoinTaskInstrumentation());
  }
}
