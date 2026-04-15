package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.sofarpc.TripleGrpcMetadataExtractAdapter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.alipay.sofa.rpc.tracer.sofatracer.TracingContextKey;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import io.grpc.Metadata;
import net.bytebuddy.asm.Advice;

public class TripleServerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.alipay.sofa.rpc.server.triple.UniqueIdInvoker";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("invoke"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("com.alipay.sofa.rpc.core.request.SofaRequest"))),
        getClass().getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      SofaRpcProtocolContext.set("tri");
      // Only extract trace context from gRPC Metadata when gRPC instrumentation is disabled.
      // When gRPC instrumentation is active, TracingServerInterceptor has already activated a
      // grpc.server span — ProviderProxyInvokerInstrumentation will call startSpan() without an
      // explicit parent and naturally become a child of that grpc.server span.
      // When gRPC instrumentation is disabled, no span is active here, so we must propagate the
      // client trace context ourselves: read the raw Metadata stored by SOFA RPC's
      // ServerReqHeaderInterceptor (Datadog headers are not reconstructed into requestProps on
      // the server side, so we must read Metadata directly).
      if (activeSpan() == null) {
        Metadata metadata = TracingContextKey.getKeyMetadata().get();
        if (metadata != null) {
          AgentSpanContext parentContext = extractContextAndGetSpanContext(metadata, GETTER);
          SofaRpcProtocolContext.setParentContext(parentContext);
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit() {
      SofaRpcProtocolContext.clear();
    }
  }
}
