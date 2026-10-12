package datadog.trace.instrumentation.netty40.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class NettyPromiseTaskInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public NettyPromiseTaskInstrumentation() {
    // Share both names with RunnableFutureInstrumentation because it captures continuations that
    // this instrumentation activates for Netty tasks; either name must enable both halves.
    super("java_concurrent", "runnable-future");
  }

  @Override
  public String hierarchyMarkerType() {
    return RunnableFuture.class.getName();
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(nameEndsWith(".netty.util.concurrent.PromiseTask"))
        .and(implementsInterface(named(RunnableFuture.class.getName())))
        .or(nameEndsWith(".netty.util.concurrent.DefaultPromise"));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.RunnableFuture", State.class.getName());
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ScheduledTaskScope"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("cancel"))
            .and(takesArguments(boolean.class))
            .and(isDeclaredBy(nameEndsWith(".netty.util.concurrent.DefaultPromise"))),
        getClass().getName() + "$Cancel");
    transformer.applyAdvice(
        isMethod()
            .and(named("setUncancellableInternal"))
            .and(takesNoArguments())
            .and(isDeclaredBy(not(declaresMethod(named("runTask"))))),
        getClass().getName() + "$ExecutionDecision");
    transformer.applyAdvice(
        isMethod().and(named("runTask")).and(takesNoArguments()),
        getClass().getName() + "$RunTask");
    transformer.applyAdvice(
        isMethod()
            .and(named("run"))
            .and(takesNoArguments())
            .and(
                isDeclaredBy(
                    nameEndsWith(".netty.util.concurrent.PromiseTask")
                        .or(
                            nameEndsWith(".netty.util.concurrent.ScheduledFutureTask")
                                .and(hasSuperType(declaresMethod(named("runTask"))))))),
        getClass().getName() + "$Run");
    transformer.applyAdvice(
        isMethod()
            .and(named("run"))
            .and(takesNoArguments())
            .and(
                isDeclaredBy(
                    nameEndsWith(".netty.util.concurrent.ScheduledFutureTask")
                        .and(not(hasSuperType(declaresMethod(named("runTask"))))))),
        getClass().getName() + "$LegacyRun");
  }

  public static final class Cancel {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.This Object task, @Advice.Return boolean cancelled) {
      // Failed cancellation leaves execution owning the captured context.
      if (cancelled && task instanceof RunnableFuture) {
        cancelTask(
            InstrumentationContext.get(RunnableFuture.class, State.class),
            (RunnableFuture<?>) task);
      }
    }
  }

  public static final class ExecutionDecision {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.This RunnableFuture<?> task, @Advice.Return boolean uncancellable) {
      if (uncancellable) {
        ScheduledTaskScope.activate(
            startTaskScope(InstrumentationContext.get(RunnableFuture.class, State.class), task));
      }
    }
  }

  public static final class RunTask {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void activate(@Advice.This RunnableFuture<?> task) {
      ScheduledTaskScope.activate(
          startTaskScope(InstrumentationContext.get(RunnableFuture.class, State.class), task));
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(@Advice.Local("depth") int depth) {
      ScheduledTaskScope state = ScheduledTaskScope.CURRENT.get();
      if (state != null) {
        depth = state.depth();
        return state.scope();
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter ContextScope previous, @Advice.Local("depth") int depth) {
      ScheduledTaskScope.close(previous, depth);
    }
  }

  public static final class LegacyRun {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(
        @Advice.This RunnableFuture<?> task,
        @Advice.FieldValue("periodNanos") long periodNanos,
        @Advice.Local("depth") int depth) {
      ScheduledTaskScope state = ScheduledTaskScope.CURRENT.get();
      ContextScope previous = null;
      if (state != null) {
        previous = state.scope();
        depth = state.depth();
      }
      // Periodic tasks remain cancellable; legacy Netty invokes their Callable directly in run().
      if (periodNanos != 0) {
        ScheduledTaskScope.activate(
            startTaskScope(InstrumentationContext.get(RunnableFuture.class, State.class), task));
      }
      return previous;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter ContextScope previous, @Advice.Local("depth") int depth) {
      ScheduledTaskScope.close(previous, depth);
    }
  }
}
