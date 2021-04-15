package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractExecutorInstrumentation extends Instrumenter.Tracing {

  private static final Logger log = LoggerFactory.getLogger(AbstractExecutorInstrumentation.class);

  public static final String EXEC_NAME = "java_concurrent";

  private final boolean TRACE_ALL_EXECUTORS = Config.get().isTraceExecutorsAll();

  /**
   * Only apply executor instrumentation to whitelisted executors. To apply to all executors, use
   * override setting above.
   */
  private final Collection<String> PERMITTED_EXECUTORS;

  public AbstractExecutorInstrumentation(final String... additionalNames) {
    super(EXEC_NAME, additionalNames);

    if (TRACE_ALL_EXECUTORS) {
      log.warn("Tracing all executors enabled. This is not a recommended setting.");
      PERMITTED_EXECUTORS = Collections.emptyList();
    } else {
      final String[] whitelist = {
        "kotlinx.coroutines.scheduling.CoroutineScheduler",
        "play.api.libs.streams.Execution$trampoline$",
        "scala.concurrent.Future$InternalCallbackExecutor$",
        "scala.concurrent.impl.ExecutionContextImpl",
        "org.eclipse.jetty.util.thread.QueuedThreadPool",
        "org.eclipse.jetty.util.thread.ReservedThreadExecutor"
      };

      final Set<String> executors = new HashSet<>(Config.get().getTraceExecutors());
      executors.addAll(Arrays.asList(whitelist));

      PERMITTED_EXECUTORS = Collections.unmodifiableSet(executors);
    }
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    if (TRACE_ALL_EXECUTORS) {
      return implementsInterface(named(Executor.class.getName()));
    } else {
      return namedOneOf(PERMITTED_EXECUTORS);
    }
  }
}
