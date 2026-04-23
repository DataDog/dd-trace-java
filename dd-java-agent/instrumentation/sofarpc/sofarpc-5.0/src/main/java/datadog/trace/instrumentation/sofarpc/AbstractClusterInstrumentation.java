package datadog.trace.instrumentation.sofarpc;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.sofarpc.SofaRpcClientDecorator.DECORATE;
import static datadog.trace.instrumentation.sofarpc.SofaRpcClientDecorator.SOFA_RPC_CLIENT;
import static datadog.trace.instrumentation.sofarpc.SofaRpcInjectAdapter.SETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.alipay.sofa.rpc.client.AbstractCluster;
import com.alipay.sofa.rpc.config.ConsumerConfig;
import com.alipay.sofa.rpc.core.request.SofaRequest;
import com.alipay.sofa.rpc.core.response.SofaResponse;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class AbstractClusterInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.alipay.sofa.rpc.client.AbstractCluster";
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
    public static AgentScope enter(
        @Advice.This AbstractCluster self, @Advice.Argument(0) SofaRequest request) {
      ConsumerConfig config = self.getConsumerConfig();
      String protocol = config != null ? config.getProtocol() : null;
      AgentSpan span = startSpan("sofarpc", SOFA_RPC_CLIENT);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      if (protocol != null) {
        span.setTag("sofarpc.protocol", protocol);
      }
      AgentScope scope = activateSpan(span);
      defaultPropagator().inject(span, request, SETTER);
      return scope;
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
