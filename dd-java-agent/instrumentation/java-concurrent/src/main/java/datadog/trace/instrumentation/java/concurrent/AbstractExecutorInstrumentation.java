package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
public abstract class AbstractExecutorInstrumentation extends Instrumenter.Default {

  public static final String EXEC_NAME = "java_concurrent";

  private final boolean TRACE_ALL_EXECUTORS = Config.get().isTraceExecutorsAll();

  /**
   * Only apply executor instrumentation to whitelisted executors. To apply to all executors, use
   * override setting above.
   */
  private final Collection<String> PERMITTED_EXECUTORS;

  /**
   * Some frameworks have their executors defined as anon classes inside other classes. Referencing
   * anon classes by name would be fragile, so instead we will use list of class prefix names. Since
   * checking this list is more expensive (O(n)) we should try to keep it short.
   */
  private final Collection<String> PERMITTED_EXECUTORS_PREFIXES;

  public AbstractExecutorInstrumentation(final String... additionalNames) {
    super(EXEC_NAME, additionalNames);

    if (TRACE_ALL_EXECUTORS) {
      log.warn("Tracing all executors enabled. This is not a recommended setting.");
      PERMITTED_EXECUTORS = Collections.emptyList();
      PERMITTED_EXECUTORS_PREFIXES = Collections.emptyList();
    } else {
      final String[] whitelist = {
        "play.api.libs.streams.Execution$trampoline$",
        "scala.concurrent.Future$InternalCallbackExecutor$",
        "scala.concurrent.impl.ExecutionContextImpl",
        "org.eclipse.jetty.util.thread.QueuedThreadPool",
        "org.eclipse.jetty.util.thread.ReservedThreadExecutor"
      };

      final Set<String> executors = new HashSet<>(Config.get().getTraceExecutors());
      executors.addAll(Arrays.asList(whitelist));

      PERMITTED_EXECUTORS = Collections.unmodifiableSet(executors);

      final String[] whitelistPrefixes = {"slick.util.AsyncExecutor$"};
      PERMITTED_EXECUTORS_PREFIXES =
          Collections.unmodifiableCollection(Arrays.asList(whitelistPrefixes));
    }
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    ElementMatcher.Junction<TypeDescription> matcher = any();
    final ElementMatcher.Junction<TypeDescription> hasExecutorInterfaceMatcher =
        implementsInterface(named(Executor.class.getName()));
    if (!TRACE_ALL_EXECUTORS) {
      matcher =
          matcher.and(
              new ElementMatcher<TypeDescription>() {
                @Override
                public boolean matches(final TypeDescription target) {
                  boolean whitelisted = PERMITTED_EXECUTORS.contains(target.getName());

                  // Check for possible prefixes match only if not whitelisted already
                  if (!whitelisted) {
                    for (final String name : PERMITTED_EXECUTORS_PREFIXES) {
                      if (target.getName().startsWith(name)) {
                        whitelisted = true;
                        break;
                      }
                    }
                  }

                  if (!whitelisted
                      && log.isDebugEnabled()
                      && hasExecutorInterfaceMatcher.matches(target)) {
                    log.debug("Skipping executor instrumentation for {}", target.getName());
                  }
                  return whitelisted;
                }
              });
    }
    return matcher.and(hasExecutorInterfaceMatcher); // Apply expensive matcher last.
  }
}
