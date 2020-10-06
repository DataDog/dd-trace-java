package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrumentation to catch the start of the RunnableFuture lifecycle by instrumenting newTaskFor,
 * applied to all extensions except for ForkJoinPools
 */
@AutoService(Instrumenter.class)
public class AbstractExecutorServiceInstrumentation extends FilteringExecutorInstrumentation {

  public AbstractExecutorServiceInstrumentation() {
    super("java_concurrent", "abstract-executor-service");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return any()
        .and(super.typeMatcher())
        .and(extendsClass(named("java.util.concurrent.AbstractExecutorService")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.RunnableFuture", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>(4);
    // find entry point by matching the constructor
    transformers.put(
        isMethod().and(namedOneOf("newTaskFor", "decorateTask")), getClass().getName() + "$Wrap");
    // entry points for RunnableFuture tasks in executors which don't have standard
    // task decoration mechanics - e.g. io.netty.util.concurrent.AbstractScheduledEventExecutor
    // go here.
    // frameworks like netty - shaded or otherwise - are used in so many places that it's
    // worth special casing here to centralise the task lifecycle approach, so whilst this
    // breaks framework/instrumentation modularity, it's probably a worthwhile tradeoff.
    transformers.put(
        isMethod()
            .and(named("schedule"))
            .and(returns(nameEndsWith("netty.util.concurrent.ScheduledFuture")))
            .and(takesArgument(0, nameEndsWith("netty.util.concurrent.ScheduledFutureTask"))),
        getClass().getName() + "$Intercept");
    return Collections.unmodifiableMap(transformers);
  }

  public static final class Wrap {
    @Advice.OnMethodExit
    public static <T> void wrap(
        @Advice.Return RunnableFuture<T> runnableFuture, @Advice.Argument(0) Object wrapped) {
      if (!exclude(RUNNABLE, wrapped)) {
        TraceScope activeScope = activeScope();
        if (null != activeScope) {
          InstrumentationContext.get(RunnableFuture.class, State.class)
              .putIfAbsent(runnableFuture, State.FACTORY)
              .captureAndSetContinuation(activeScope);
        }
      }
    }
  }

  public static final class Intercept {
    @Advice.OnMethodEnter
    public static <T> void intercept(@Advice.Argument(0) RunnableFuture<T> runnableFuture) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(RunnableFuture.class, State.class)
            .putIfAbsent(runnableFuture, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }
}
