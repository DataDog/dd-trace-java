package datadog.trace.instrumentation.akka.concurrent;

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
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import akka.dispatch.forkjoin.ForkJoinTask;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instrument {@link ForkJoinTask}.
 *
 * <p>Note: There are quite a few separate implementations of {@code ForkJoinTask}/{@code
 * ForkJoinPool}: JVM, Akka, Scala, Netty to name a few. This class handles Akka version.
 */
@AutoService(InstrumenterModule.class)
public final class AkkaForkJoinTaskInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public AkkaForkJoinTaskInstrumentation() {
    super("java_concurrent", "akka_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.forkjoin.ForkJoinTask", State.class.getName());
  }

  @Override
  public String hierarchyMarkerType() {
    String akkaForkJoinTaskName = InstrumenterConfig.get().getAkkaForkJoinTaskName();
    return akkaForkJoinTaskName != null && !akkaForkJoinTaskName.isEmpty()
        ? null // bypass the hint if custom class is configured
        : "akka.dispatch.forkjoin.ForkJoinTask";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return notExcludedByName(FORK_JOIN_TASK)
        .and(declaresMethod(namedOneOf("exec", "fork", "cancel")))
        .and(isForkJoinTaskSubclass());
  }

  private ElementMatcher<TypeDescription> isForkJoinTaskSubclass() {
    ElementMatcher.Junction<TypeDescription> forkJoinTaskSubclass =
        extendsClass(named("akka.dispatch.forkjoin.ForkJoinTask"));
    String akkaForkJoinTaskName = InstrumenterConfig.get().getAkkaForkJoinTaskName();
    return akkaForkJoinTaskName != null && !akkaForkJoinTaskName.isEmpty()
        ? forkJoinTaskSubclass.or(extendsClass(named(akkaForkJoinTaskName)))
        : forkJoinTaskSubclass;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    Map<ExcludeFilter.ExcludeType, Collection<String>> exclude =
        new EnumMap<>(ExcludeFilter.ExcludeType.class);
    exclude.put(
        FORK_JOIN_TASK,
        Arrays.asList(
            "akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask",
            "akka.dispatch.Dispatcher$$anon$1"));
    exclude.put(
        RUNNABLE_FUTURE,
        Arrays.asList(
            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedCallable",
            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnable",
            "akka.dispatch.forkjoin.ForkJoinTask$AdaptedRunnableAction"));
    return exclude;
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
      capture(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }
  }

  public static final class Cancel {
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static <T> void cancel(@Advice.This ForkJoinTask<T> task) {
      cancelTask(InstrumentationContext.get(ForkJoinTask.class, State.class), task);
    }
  }
}
