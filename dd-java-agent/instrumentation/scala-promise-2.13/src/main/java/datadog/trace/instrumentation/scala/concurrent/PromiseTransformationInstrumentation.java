package datadog.trace.instrumentation.scala.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.impl.Promise.Transformation;

@AutoService(Instrumenter.class)
public final class PromiseTransformationInstrumentation extends Instrumenter.Default
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
    return singletonMap("scala.concurrent.impl.Promise$Transformation", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformations = new HashMap<>(4);
    transformations.put(isConstructor(), getClass().getName() + "$Construct");
    transformations.put(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformations.put(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
    return unmodifiableMap(transformations);
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, Set<String>> excludedClasses() {
    // make sure nothing else instruments this
    return singletonMap(
        RUNNABLE, Collections.singleton("scala.concurrent.impl.Promise$Transformation"));
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

    /** Promise.Transformation was introduced in scala 2.13 */
    private static void muzzleCheck(final Transformation callback) {
      callback.submitWithValue(null);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <F, T> TraceScope before(@Advice.This Transformation<F, T> task) {
      State state = InstrumentationContext.get(Transformation.class, State.class).get(task);
      if (null != state) {
        TraceScope.Continuation continuation = state.getAndResetContinuation();
        if (null != continuation) {
          return continuation.activate();
        }
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter TraceScope scope) {
      if (null != scope) {
        scope.close();
      }
    }

    /** Promise.Transformation was introduced in scala 2.13 */
    private static void muzzleCheck(final Transformation callback) {
      callback.submitWithValue(null);
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

    /** Promise.Transformation was introduced in scala 2.13 */
    private static void muzzleCheck(final Transformation callback) {
      callback.submitWithValue(null);
    }
  }
}
