package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class FutureTaskInstrumentation extends Instrumenter.Default {

  public FutureTaskInstrumentation() {
    super("java-concurrent", "future-task");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("java.util.concurrent.FutureTask");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("java.util.concurrent.FutureTask", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>(4);
    transformers.put(isConstructor(), getClass().getName() + "$Construct");
    transformers.put(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformers.put(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
    return Collections.unmodifiableMap(transformers);
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static <T> void wrap(@Advice.This FutureTask<T> futureTask) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(activeScope);
        InstrumentationContext.get(FutureTask.class, State.class).put(futureTask, state);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> TraceScope before(@Advice.This FutureTask<T> futureTask) {
      State state = InstrumentationContext.get(FutureTask.class, State.class).get(futureTask);
      if (null != state) {
        TraceScope.Continuation continuation = state.getAndResetContinuation();
        if (null != continuation) {
          return continuation.activate();
        }
      }
      return null;
    }

    @Advice.OnMethodExit
    public static void after(@Advice.Enter TraceScope scope) {
      if (null != scope) {
        scope.close();
      }
    }
  }

  public static final class Cancel {
    @Advice.OnMethodExit
    public static <T> void cancel(@Advice.This FutureTask<T> futureTask) {
      State state = InstrumentationContext.get(FutureTask.class, State.class).get(futureTask);
      if (null != state) {
        state.closeContinuation();
      }
    }
  }
}
