package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import java.util.Collection;
import java.util.concurrent.Executor;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractExecutorInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.CanShortcutTypeMatching,
        Instrumenter.ForConfiguredTypes {

  private static final Logger log = LoggerFactory.getLogger(AbstractExecutorInstrumentation.class);

  public static final String EXEC_NAME = "java_concurrent";

  /** To apply to all executors, use override setting below. */
  private final boolean TRACE_ALL_EXECUTORS = InstrumenterConfig.get().isTraceExecutorsAll();

  public AbstractExecutorInstrumentation(final String... additionalNames) {
    super(EXEC_NAME, additionalNames);

    if (TRACE_ALL_EXECUTORS) {
      log.warn("Tracing all executors enabled. This is not a recommended setting.");
    }
  }

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
