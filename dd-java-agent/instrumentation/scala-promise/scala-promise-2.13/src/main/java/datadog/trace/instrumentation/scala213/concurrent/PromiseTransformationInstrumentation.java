package datadog.trace.instrumentation.scala213.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
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
import scala.concurrent.impl.Promise.Transformation;
import scala.util.Try;

@AutoService(Instrumenter.class)
public final class PromiseTransformationInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, ExcludeFilterProvider {

  public PromiseTransformationInstrumentation() {
    super("scala_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "scala.concurrent.impl.Promise$Transformation";
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("scala.concurrent.impl.Promise$Transformation", State.class.getName());
    contextStore.put("scala.util.Try", AgentSpan.class.getName());
    return contextStore;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArguments(4)), getClass().getName() + "$Construct");
    transformation.applyAdvice(
        isMethod().and(named("submitWithValue")), getClass().getName() + "$SubmitWithValue");
    transformation.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformation.applyAdvice(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // force other instrumentations (e.g. Runnable) not to deal with this type
    Map<ExcludeFilter.ExcludeType, Collection<String>> map = new HashMap<>();
    Collection<String> pt = Collections.singleton("scala.concurrent.impl.Promise$Transformation");
    map.put(RUNNABLE, pt);
    map.put(EXECUTOR, pt);
    return map;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.scala.PromiseHelper"};
  }

  public static final class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <F, T> void onConstruct(
        @Advice.This Transformation<F, T> task, @Advice.Argument(3) int xform) {
      // Do not trace the Noop Transformation
      if (xform == 0) {
        return;
      }
      capture(InstrumentationContext.get(Transformation.class, State.class), task, true);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <F, T> AgentScope before(@Advice.This Transformation<F, T> task) {
      return PromiseHelper.runActivateSpan(
          InstrumentationContext.get(Transformation.class, State.class).get(task));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class Cancel {
    @Advice.OnMethodEnter
    public static <F, T> void cancel(@Advice.This Transformation<F, T> task) {
      State state = InstrumentationContext.get(Transformation.class, State.class).get(task);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }

  public static final class SubmitWithValue {
    @Advice.OnMethodEnter
    public static <F, T> void beforeExecute(
        @Advice.This Transformation<F, T> task, @Advice.Argument(value = 0) Try<T> resolved) {
      // About to enter an ExecutionContext so capture the Scope if necessary
      ContextStore<Transformation, State> contextStore =
          InstrumentationContext.get(Transformation.class, State.class);
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
        capture(contextStore, task, true);
      }
    }
  }
}
