package stackstate.trace.instrumentation.java.concurrent;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.instrumentation.java.concurrent.ExecutorInstrumentation.ConcurrentUtils;
import stackstate.trace.instrumentation.java.concurrent.ExecutorInstrumentation.DatadogWrapper;
import java.util.*;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public final class FutureInstrumentation extends Instrumenter.Default {

  /**
   * Only apply executor instrumentation to whitelisted executors. In the future, this restriction
   * may be lifted to include all executors.
   */
  private static final Collection<String> WHITELISTED_FUTURES;

  static {
    final String[] whitelist = {
      "java.util.concurrent.ForkJoinTask",
      "java.util.concurrent.CountedCompleter",
      "java.util.concurrent.ForkJoinTask$AdaptedCallable",
      "java.util.concurrent.ForkJoinTask$RunnableExecuteAction",
      "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask",
      "java.util.concurrent.FutureTask",
      "java.util.concurrent.ExecutorCompletionService$QueueingFuture",
      "java.util.concurrent.RecursiveAction",
      "scala.concurrent.forkjoin.ForkJoinTask",
      "scala.concurrent.impl.ExecutionContextImpl$AdaptedForkJoinTask",
      "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnableAction",
      "scala.concurrent.forkjoin.ForkJoinTask$AdaptedCallable",
      "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnable",
      "scala.collection.parallel.AdaptiveWorkStealingForkJoinTasks$WrappedTask",
      "akka.dispatch.Mailbox",
      "akka.dispatch.forkjoin.ForkJoinTask",
      "akka.dispatch.Mailboxes$$anon$1",
      "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnableAction",
      "akka.dispatch.forkjoin.ForkJoinTask$AdaptedCallable",
      "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnable",
      "akka.dispatch.Dispatcher$$anon$1",
      "akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask",
      "com.google.common.util.concurrent.SettableFuture",
      "com.google.common.util.concurrent.AbstractFuture$TrustedFuture",
      "com.google.common.util.concurrent.AbstractFuture"
    };
    WHITELISTED_FUTURES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(whitelist)));
  }

  public FutureInstrumentation() {
    super(ExecutorInstrumentation.EXEC_NAME);
  }

  @Override
  public ElementMatcher typeMatcher() {
    return not(isInterface())
        .and(hasSuperType(named(Future.class.getName())))
        .and(
            new ElementMatcher<TypeDescription>() {
              @Override
              public boolean matches(final TypeDescription target) {
                final boolean whitelisted = WHITELISTED_FUTURES.contains(target.getName());
                if (!whitelisted) {
                  log.debug("Skipping future instrumentation for {}", target.getName());
                }
                return whitelisted;
              }
            });
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      ExecutorInstrumentation.class.getName() + "$ConcurrentUtils",
      ExecutorInstrumentation.class.getName() + "$DatadogWrapper",
      ExecutorInstrumentation.class.getName() + "$CallableWrapper",
      ExecutorInstrumentation.class.getName() + "$RunnableWrapper"
    };
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        named("cancel").and(returns(boolean.class)), CanceledFutureAdvice.class.getName());
    return transformers;
  }

  public static class CanceledFutureAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static DatadogWrapper findWrapper(@Advice.This final Future<?> future) {
      return ConcurrentUtils.getDatadogWrapper(future);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void abortTracing(
        @Advice.Enter final DatadogWrapper wrapper, @Advice.Return final boolean canceled) {
      if (canceled && null != wrapper) {
        wrapper.cancel();
      }
    }
  }
}
