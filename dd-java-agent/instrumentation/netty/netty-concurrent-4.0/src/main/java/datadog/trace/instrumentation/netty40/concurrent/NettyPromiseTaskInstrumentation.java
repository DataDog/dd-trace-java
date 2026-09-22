package datadog.trace.instrumentation.netty40.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ScheduledFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class NettyPromiseTaskInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public NettyPromiseTaskInstrumentation() {
    // Use the same names as RunnableFutureInstrumentation: it captures the continuation and
    // delegates scheduled task activation here. A separate netty-concurrent toggle could disable
    // this half of propagation when only java_concurrent or runnable-future is enabled.
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
        .and(hasSuperType(declaresMethod(named("runTask"))));
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
        isMethod().and(named("runTask")).and(takesNoArguments()),
        getClass().getName() + "$RunTask");
    transformer.applyAdvice(
        isMethod()
            .and(named("run"))
            .and(takesNoArguments())
            .and(isDeclaredBy(nameEndsWith(".netty.util.concurrent.ScheduledFutureTask"))),
        getClass().getName() + "$Run");
  }

  public static final class RunTask {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void activate(@Advice.This RunnableFuture<?> task) {
      if (task instanceof ScheduledFuture) {
        ContextScope scope =
            startTaskScope(InstrumentationContext.get(RunnableFuture.class, State.class), task);
        if (scope != null) {
          ScheduledTaskScope state = ScheduledTaskScope.CURRENT.get();
          if (state == null) {
            state = new ScheduledTaskScope();
            ScheduledTaskScope.CURRENT.set(state);
          }
          state.scope = scope;
          state.depth++;
        }
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(@Advice.Local("depth") int depth) {
      ScheduledTaskScope state = ScheduledTaskScope.CURRENT.get();
      if (state != null) {
        depth = state.depth;
        return state.scope;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter ContextScope previous, @Advice.Local("depth") int depth) {
      ScheduledTaskScope state = ScheduledTaskScope.CURRENT.get();
      if (state != null && state.depth > depth) {
        ContextScope scope = state.scope;
        state.scope = previous;
        state.depth = depth;
        endTaskScope(scope);
      }
    }
  }
}
