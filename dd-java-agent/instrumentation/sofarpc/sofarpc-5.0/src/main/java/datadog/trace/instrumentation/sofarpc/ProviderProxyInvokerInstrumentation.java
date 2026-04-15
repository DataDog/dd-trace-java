package datadog.trace.instrumentation.sofarpc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.sofarpc.SofaRpcExtractAdapter.GETTER;
import static datadog.trace.instrumentation.sofarpc.SofaRpcServerDecorator.DECORATE;
import static datadog.trace.instrumentation.sofarpc.SofaRpcServerDecorator.SOFA_RPC_SERVER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import net.bytebuddy.asm.Advice;

public class ProviderProxyInvokerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.alipay.sofa.rpc.server.ProviderProxyInvoker";
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
    public static AgentScope enter(@Advice.Argument(0) SofaRequest request) {
      // Protocol is set in thread-local by transport-specific instrumentation before this call.
      // If null, the transport is not instrumented — skip.
      String protocol = SofaRpcProtocolContext.get();
      if (protocol == null) {
        return null;
      }
      AgentSpan span;
      if ("bolt".equals(protocol) || "h2c".equals(protocol)) {
        // Bolt propagates Datadog trace headers via SofaRequest.requestProps — extract from there.
        AgentSpanContext parentContext = extractContextAndGetSpanContext(request, GETTER);
        span = startSpan(SOFA_RPC_SERVER, parentContext);
      } else {
        // For Triple and other protocols, trace context is propagated at the transport layer
        // (e.g. gRPC Metadata). The transport instrumentation already activated the parent span,
        // so startSpan without an explicit parent inherits it automatically.
        span = startSpan(SOFA_RPC_SERVER);
      }
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      span.setTag("sofarpc.protocol", protocol);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter AgentScope scope,
        @Advice.Return SofaResponse response,
        @Advice.Thrown Throwable throwable) {
      if (scope == null) {
        return;
      }
      AgentSpan span = scope.span();
      DECORATE.onResponse(span, response);
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
