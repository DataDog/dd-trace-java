package datadog.trace.instrumentation.scala210.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.instrumentation.scala.PromiseHelper;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import scala.concurrent.impl.CallbackRunnable;
import scala.util.Try;

@AutoService(InstrumenterModule.class)
public class CallbackRunnableInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice, ExcludeFilterProvider {

  public CallbackRunnableInstrumentation() {
    super("scala_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "scala.concurrent.impl.CallbackRunnable";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("scala.concurrent.impl.CallbackRunnable", State.class.getName());
    contextStore.put("scala.util.Try", AgentSpan.class.getName());
    return contextStore;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformer.applyAdvice(
        isMethod().and(named("executeWithValue")), getClass().getName() + "$ExecuteWithValue");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // force other instrumentations (e.g. Runnable) not to deal with this type
    Map<ExcludeFilter.ExcludeType, Collection<String>> map = new HashMap<>();
    Collection<String> cbr = Collections.singleton("scala.concurrent.impl.CallbackRunnable");
    map.put(RUNNABLE, cbr);
    map.put(EXECUTOR, cbr);
    return map;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.scala.PromiseHelper"};
  }

  /** Capture the scope when the promise is created */
  public static final class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <T> void onConstruct(@Advice.This CallbackRunnable<T> task) {
      capture(InstrumentationContext.get(CallbackRunnable.class, State.class), task);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> AgentScope before(@Advice.This CallbackRunnable<T> task) {
      return PromiseHelper.runActivateSpan(
          InstrumentationContext.get(CallbackRunnable.class, State.class).get(task));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class ExecuteWithValue {
    @Advice.OnMethodEnter
    public static <T> void beforeExecute(
        @Advice.This CallbackRunnable<T> task, @Advice.Argument(value = 0) Try<T> resolved) {
      // About to enter an ExecutionContext so capture the Scope if necessary
      ContextStore<CallbackRunnable, State> contextStore =
          InstrumentationContext.get(CallbackRunnable.class, State.class);
      State state = contextStore.get(task);
      if (PromiseHelper.completionPriority) {
        state =
            PromiseHelper.executeCaptureSpan(
                InstrumentationContext.get(Try.class, AgentSpan.class),
                resolved,
                contextStore,
                task,
                state);
      }
      // If nothing else has been picked up, then try to pick up the current Scope
      if (null == state) {
        capture(contextStore, task);
      }
    }
  }
}
