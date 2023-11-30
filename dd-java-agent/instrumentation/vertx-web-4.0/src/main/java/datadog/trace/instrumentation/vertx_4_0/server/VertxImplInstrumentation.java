package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.core.Handler;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class VertxImplInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public VertxImplInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.impl.VertxImpl";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BlockingExceptionHandler",
      packageName + ".VertxDecorator",
      packageName + ".VertxDecorator$VertxURIDataAdapter",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isPublic()
            .and(named("exceptionHandler"))
            .and(takesArguments(0))
            .and(returns(named("io.vertx.core.Handler"))),
        VertxImplInstrumentation.class.getName() + "$ExceptionHandlerAdvice");
  }

  static class ExceptionHandlerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return(readOnly = false) Handler<Throwable> ret) {
      AgentSpan agentSpan = AgentTracer.activeSpan();
      if (agentSpan != null) {
        ret = new BlockingExceptionHandler(agentSpan, ret);
      }
    }
  }
}
