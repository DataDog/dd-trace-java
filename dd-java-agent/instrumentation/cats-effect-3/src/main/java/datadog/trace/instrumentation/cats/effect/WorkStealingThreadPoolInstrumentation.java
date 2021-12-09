package datadog.trace.instrumentation.cats.effect;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;

import cats.effect.IOFiber;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class WorkStealingThreadPoolInstrumentation extends Instrumenter.Tracing {
  private static final String[] ALL_INSTRUMENTATION_NAMES = {
    "experimental.cats-effect",
    // we want to enable it for http4s
    "experimental.http4s-blaze-server",
    "experimental.http4s-blaze-client",
    "experimental.http4s-server",
    "experimental.http4s",
    "experimental"
  };

  public WorkStealingThreadPoolInstrumentation() {
    super(
        ALL_INSTRUMENTATION_NAMES[0],
        Arrays.copyOfRange(ALL_INSTRUMENTATION_NAMES, 1, ALL_INSTRUMENTATION_NAMES.length));
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("cats.effect.unsafe.WorkStealingThreadPool");
  }

  @Override
  public Map<String, String> contextStoreForAll() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(namedOneOf("executeFiber", "scheduleFiber", "rescheduleFiber")),
        getClass().getName() + "$Capture");
  }

  public static class Capture {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State enter(@Advice.Argument(0) IOFiber<?> fiber) {
      final AgentScope scope = activeScope();
      if (null != scope && scope.isAsyncPropagating()) {
        final ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        State state = ExecutorInstrumentationUtils.setupState(contextStore, fiber, scope);
        state.startThreadMigration();
        return state;
      } else {
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exitJ(
        @Advice.Enter final State state, @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(null, state, throwable);
    }
  }
}
