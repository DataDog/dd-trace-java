package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.notExcludedByName;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.FORK_JOIN_TASK;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
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
@AutoService(Instrumenter.class)
public final class JavaForkJoinTaskInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy, ExcludeFilterProvider {

  public JavaForkJoinTaskInstrumentation() {
    super(AbstractExecutorInstrumentation.EXEC_NAME);
  }

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
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.ForkJoinTask", State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(namedOneOf("doExec", "exec")), getClass().getName() + "$Exec");
    transformation.applyAdvice(isMethod().and(named("fork")), getClass().getName() + "$Fork");
    transformation.applyAdvice(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
  }

  @Override
  public Map<ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        RUNNABLE_FUTURE,
        Arrays.asList(
            "java.util.concurrent.ForkJoinTask$AdaptedCallable",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnable",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnableAction",
            "java.util.concurrent.ForkJoinTask$AdaptedInterruptibleCallable"));
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
        capture(InstrumentationContext.get(ForkJoinTask.class, State.class), task, true);
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
