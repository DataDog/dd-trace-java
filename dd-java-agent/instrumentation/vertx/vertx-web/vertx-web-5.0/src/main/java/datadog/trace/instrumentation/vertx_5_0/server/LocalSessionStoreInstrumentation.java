package datadog.trace.instrumentation.vertx_5_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import net.bytebuddy.asm.Advice;

/** Keeps session-store maintenance timers from retaining an unrelated request's continuation. */
@AutoService(InstrumenterModule.class)
public final class LocalSessionStoreInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public LocalSessionStoreInstrumentation() {
    super("vertx", "vertx-5.0");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.sstore.impl.LocalSessionStoreImpl";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_HEADERS_INTERNAL};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // Initialization also starts the PRNG refresh timer; neither timer belongs to a request.
    transformer.applyAdvice(
        named("init")
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("io.vertx.core.Vertx")))
            .and(takesArgument(1, named("io.vertx.core.json.JsonObject"))),
        getClass().getName() + "$DisablePropagationAdvice");
    transformer.applyAdvice(
        named("setTimer").and(isPrivate()).and(takesNoArguments()).and(returns(void.class)),
        getClass().getName() + "$DisablePropagationAdvice");
  }

  public static final class DisablePropagationAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean before() {
      if (isAsyncPropagationEnabled()) {
        setAsyncPropagationEnabled(false);
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter boolean wasEnabled) {
      if (wasEnabled) {
        setAsyncPropagationEnabled(true);
      }
    }
  }
}
