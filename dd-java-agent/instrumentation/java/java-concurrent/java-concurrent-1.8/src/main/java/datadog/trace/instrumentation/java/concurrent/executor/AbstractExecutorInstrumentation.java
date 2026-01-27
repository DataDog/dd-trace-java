package datadog.trace.instrumentation.java.concurrent.executor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import java.util.Collection;
import java.util.concurrent.Executor;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractExecutorInstrumentation
    implements Instrumenter.ForBootstrap,
        Instrumenter.CanShortcutTypeMatching,
        Instrumenter.ForConfiguredTypes,
        Instrumenter.HasMethodAdvice {

  /** To apply to all executors, use override setting below. */
  private final boolean TRACE_ALL_EXECUTORS = InstrumenterConfig.get().isTraceExecutorsAll();

  @Override
  public boolean onlyMatchKnownTypes() {
    return !TRACE_ALL_EXECUTORS;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "kotlinx.coroutines.scheduling.CoroutineScheduler",
      "play.api.libs.streams.Execution$trampoline$",
      "scala.concurrent.Future$InternalCallbackExecutor$",
      "scala.concurrent.impl.ExecutionContextImpl"
    };
  }

  @Override
  public Collection<String> configuredMatchingTypes() {
    return InstrumenterConfig.get().getTraceExecutors();
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(Executor.class.getName()));
  }
}
