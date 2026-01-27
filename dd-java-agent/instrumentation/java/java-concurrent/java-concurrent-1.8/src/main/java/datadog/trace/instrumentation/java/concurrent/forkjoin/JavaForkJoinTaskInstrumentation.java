package datadog.trace.instrumentation.java.concurrent.forkjoin;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.notExcludedByName;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.concurrent.ForkJoinTask;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles JVM version.
 */
public final class JavaForkJoinTaskInstrumentation
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return notExcludedByName(FORK_JOIN_TASK)
        .and(declaresMethod(namedOneOf("doExec", "exec", "fork", "cancel")))
        .and(extendsClass(named("java.util.concurrent.ForkJoinTask")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("doExec", "exec")), getClass().getName() + "$Exec");
    transformer.applyAdvice(isMethod().and(named("fork")), getClass().getName() + "$Fork");
    transformer.applyAdvice(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
  }

  public static final class Exec {
    @Advice.OnMethodEnter
    public static <T> AgentScope before(@Advice.This ForkJoinTask<T> task) {
      return exclude(FORK_JOIN_TASK, task)
          ? null
          : startTaskScope(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class Fork {
    @Advice.OnMethodEnter
    public static <T> void fork(@Advice.This ForkJoinTask<T> task) {
      if (!exclude(FORK_JOIN_TASK, task)) {
        capture(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
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
