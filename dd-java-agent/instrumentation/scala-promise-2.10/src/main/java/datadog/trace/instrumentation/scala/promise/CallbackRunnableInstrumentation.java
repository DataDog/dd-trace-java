package datadog.trace.instrumentation.scala.promise;

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
import scala.concurrent.impl.CallbackRunnable;

@AutoService(Instrumenter.class)
public class CallbackRunnableInstrumentation extends Instrumenter.Default
    implements ExcludeFilterProvider {

  public CallbackRunnableInstrumentation() {
    super("scala_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("scala.concurrent.impl.CallbackRunnable");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.concurrent.impl.CallbackRunnable", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformations = new HashMap<>(4);
    transformations.put(isConstructor(), getClass().getName() + "$Construct");
    transformations.put(isMethod().and(named("run")), getClass().getName() + "$Run");
    return unmodifiableMap(transformations);
  }

  @Override
  public boolean isEnabled() {
    return true;
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, Set<String>> excludedClasses() {
    // force other instrumentations (e.g. Runnable) not to deal with this type
    return singletonMap(RUNNABLE, Collections.singleton("scala.concurrent.impl.CallbackRunnable"));
  }

  /** Capture the scope when the promise is created */
  public static final class Construct {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <T> void onConstruct(@Advice.This CallbackRunnable<T> task) {
      final TraceScope scope = activeScope();
      if (scope != null) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(scope);
        InstrumentationContext.get(CallbackRunnable.class, State.class).put(task, state);
      }
    }

    /** CallbackRunnable was introduced in scala 2.10 */
    private static void muzzleCheck(final CallbackRunnable<?> callback) {
      callback.executeWithValue(null);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> TraceScope before(@Advice.This CallbackRunnable<T> task) {
      State state = InstrumentationContext.get(CallbackRunnable.class, State.class).get(task);
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

    /** CallbackRunnable was introduced in scala 2.10 */
    private static void muzzleCheck(final CallbackRunnable<?> callback) {
      callback.executeWithValue(null);
    }
  }
}
