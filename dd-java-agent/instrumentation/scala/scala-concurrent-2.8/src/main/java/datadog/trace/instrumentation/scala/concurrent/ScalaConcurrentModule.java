package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class ScalaConcurrentModule extends InstrumenterModule.ContextTracking
    implements ExcludeFilterProvider {

  public ScalaConcurrentModule() {
    super("java_concurrent", "scala_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.concurrent.forkjoin.ForkJoinTask", State.class.getName());
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        RUNNABLE_FUTURE,
        Arrays.asList(
            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedCallable",
            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnable",
            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnableAction"));
  }

  @Override
  public List<Instrumenter> typeInstrumentations() {
    return Arrays.asList(
        new ScalaForkJoinTaskInstrumentation(), new ScalaForkJoinPoolInstrumentation());
  }
}
