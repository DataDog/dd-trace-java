package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;

import akka.dispatch.forkjoin.ForkJoinTask;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles Akka version.
 */
@AutoService(Instrumenter.class)
public final class AkkaForkJoinTaskInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {

  public AkkaForkJoinTaskInstrumentation() {
    super("java_concurrent", "akka_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.forkjoin.ForkJoinTask", State.class.getName());
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return not(named("akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask"))
        .and(ElementMatchers.<TypeDescription>declaresMethod(namedOneOf("exec", "fork", "cancel")))
        .and(extendsClass(named("akka.dispatch.forkjoin.ForkJoinTask")));
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        RUNNABLE_FUTURE,
        Arrays.asList(
            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedCallable",
            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnable",
            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnableAction"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(namedOneOf("doExec", "exec")), getClass().getName() + "$Exec");
    transformation.applyAdvice(isMethod().and(named("fork")), getClass().getName() + "$Fork");
    transformation.applyAdvice(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
  }

  public static final class Exec {
    @Advice.OnMethodEnter
    public static <T> TraceScope before(@Advice.This ForkJoinTask<T> task) {
      return AdviceUtils.startTaskScope(
          InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      AdviceUtils.endTaskScope(scope);
    }
  }

  public static final class Fork {
    @Advice.OnMethodEnter
    public static <T> void fork(@Advice.This ForkJoinTask<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(ForkJoinTask.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }

  public static final class Cancel {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static <T> void cancel(@Advice.This ForkJoinTask<T> task) {
      State state = InstrumentationContext.get(ForkJoinTask.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }
}
