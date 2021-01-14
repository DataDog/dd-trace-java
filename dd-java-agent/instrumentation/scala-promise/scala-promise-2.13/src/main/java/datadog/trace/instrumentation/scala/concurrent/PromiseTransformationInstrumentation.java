package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.unmodifiableMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.scala.PromiseHelper;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.impl.Promise.Transformation;
import scala.util.Try;

@AutoService(Instrumenter.class)
public final class PromiseTransformationInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {

  public PromiseTransformationInstrumentation() {
    super("scala_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("scala.concurrent.impl.Promise$Transformation");
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> contextStore = new HashMap<>();
    contextStore.put("scala.concurrent.impl.Promise$Transformation", State.class.getName());
    contextStore.put("scala.util.Try", AgentSpan.class.getName());
    return contextStore;
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformations = new HashMap<>(8);
    transformations.put(
        isConstructor().and(takesArguments(4)), getClass().getName() + "$Construct");
    transformations.put(
        isMethod().and(named("submitWithValue")), getClass().getName() + "$SubmitWithValue");
    transformations.put(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformations.put(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
    return unmodifiableMap(transformations);
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
    public static <F, T> void onConstruct(@Advice.This Transformation<F, T> task) {
      final TraceScope scope = activeScope();
      if (scope != null) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(scope);
        InstrumentationContext.get(Transformation.class, State.class).put(task, state);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <F, T> TraceScope before(@Advice.This Transformation<F, T> task) {
      return AdviceUtils.startTaskScope(
          InstrumentationContext.get(Transformation.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      AdviceUtils.endTaskScope(scope);
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
      // about to enter an ExecutionContext so capture the scope if necessary
      // (this used to happen automatically when the RunnableInstrumentation
      // was relied on, and happens anyway if the ExecutionContext is backed
      // by a wrapping Executor (e.g. FJP, ScheduledThreadPoolExecutor)
      ContextStore<Transformation, State> tStore =
          InstrumentationContext.get(Transformation.class, State.class);
      State state = tStore.get(task);
      if (PromiseHelper.completionPriority) {
        final AgentSpan span = InstrumentationContext.get(Try.class, AgentSpan.class).get(resolved);
        State oState = state;
        state = PromiseHelper.handleSpan(span, state);
        if (state != oState) {
          tStore.put(task, state);
        }
      }
      // If nothing else has been picked up, then try to pick up the current Scope
      if (null == state) {
        final TraceScope scope = activeScope();
        if (scope != null) {
          state = State.FACTORY.create();
          state.captureAndSetContinuation(scope);
          tStore.put(task, state);
        }
      }
    }
  }
}
