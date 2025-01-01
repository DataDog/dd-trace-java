package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.notExcludedByName;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
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
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.forkjoin.ForkJoinTask;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles Scala version.
 */
@AutoService(InstrumenterModule.class)
public final class ScalaForkJoinTaskInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public ScalaForkJoinTaskInstrumentation() {
    super("java_concurrent", "scala_concurrent");
  }

  @Override
  public String hierarchyMarkerType() {
    return "scala.concurrent.forkjoin.ForkJoinTask";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // this type is constructed on entry to the JFP, and can be used to track
    // the lifecycle of tasks
    return notExcludedByName(FORK_JOIN_TASK)
        .and(declaresMethod(namedOneOf("exec", "fork", "cancel")))
        .and(extendsClass(named(hierarchyMarkerType())));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.concurrent.forkjoin.ForkJoinTask", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("doExec", "exec")), getClass().getName() + "$Exec");
    transformer.applyAdvice(isMethod().and(named("fork")), getClass().getName() + "$Fork");
    transformer.applyAdvice(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return singletonMap(
        RUNNABLE_FUTURE,
        Arrays.asList(
            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedCallable",
            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnable",
            "scala.concurrent.forkjoin.ForkJoinTask$AdaptedRunnableAction"));
  }

  public static final class Exec {
    @Advice.OnMethodEnter
    public static <T> AgentScope before(@Advice.This ForkJoinTask<T> task) {
      return startTaskScope(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
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
      cancelTask(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }
  }
}
