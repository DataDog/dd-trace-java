package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

// BlockingException when decoding the urlencoded POST body is not relayed to the
// exception handling mechanism implemented in
// HttpServerRequestInstrumentation/BlockingExceptionHandler
@AutoService(InstrumenterModule.class)
public class VertxHandlerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public VertxHandlerInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.net.impl.VertxHandler";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("exceptionCaught"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("io.netty.channel.ChannelHandlerContext")))
            .and(takesArgument(1, Throwable.class)),
        VertxHandlerInstrumentation.class.getName() + "$ExceptionCaughtAdvice");
  }

  static class ExceptionCaughtAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void after(@Advice.Argument(1) Throwable t) {
      if (!(t instanceof BlockingException)) {
        return;
      }
      AgentSpan agentSpan = AgentTracer.activeSpan();
      if (agentSpan != null) {
        agentSpan.addThrowable(t);
      }
    }
  }
}
