package datadog.trace.instrumentation.java.concurrent.forkjoin;

import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.EXECUTOR_INSTRUMENTATION_NAME;
import static datadog.trace.instrumentation.java.concurrent.ConcurrentInstrumentationNames.FORK_JOIN_POOL_INSTRUMENTATION_NAME;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Groups the instrumentations for ForkJoinPool, ForkJoinTask, and WorkQueue. */
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
    List<Instrumenter> instrumenters = new ArrayList<>(2);
    instrumenters.add(new JavaForkJoinPoolInstrumentation());
    instrumenters.add(new JavaForkJoinTaskInstrumentation());
    return instrumenters;
  }
}
