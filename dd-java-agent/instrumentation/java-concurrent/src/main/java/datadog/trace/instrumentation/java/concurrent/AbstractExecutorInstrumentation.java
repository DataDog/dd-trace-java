package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractExecutorInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.CanShortcutTypeMatching {

  private static final Logger log = LoggerFactory.getLogger(AbstractExecutorInstrumentation.class);

  public static final String EXEC_NAME = "java_concurrent";

  private final boolean TRACE_ALL_EXECUTORS = Config.get().isTraceExecutorsAll();

  /**
   * Only apply executor instrumentation to whitelisted executors. To apply to all executors, use
   * override setting above.
   */
  private final String[] PERMITTED_EXECUTORS;

  public AbstractExecutorInstrumentation(final String... additionalNames) {
    super(EXEC_NAME, additionalNames);

    if (TRACE_ALL_EXECUTORS) {
      log.warn("Tracing all executors enabled. This is not a recommended setting.");
      PERMITTED_EXECUTORS = new String[0];
    } else {
      final String[] whitelist = {
        "kotlinx.coroutines.scheduling.CoroutineScheduler",
        "play.api.libs.streams.Execution$trampoline$",
        "scala.concurrent.Future$InternalCallbackExecutor$",
        "scala.concurrent.impl.ExecutionContextImpl"
      };

      final Set<String> executors = new HashSet<>(Config.get().getTraceExecutors());
      executors.addAll(Arrays.asList(whitelist));

      PERMITTED_EXECUTORS = executors.toArray(new String[0]);
    }
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return !TRACE_ALL_EXECUTORS;
  }

  @Override
  public String[] knownMatchingTypes() {
    return PERMITTED_EXECUTORS;
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
