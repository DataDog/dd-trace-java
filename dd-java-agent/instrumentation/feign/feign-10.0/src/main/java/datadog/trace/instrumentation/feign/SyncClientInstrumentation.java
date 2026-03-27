package datadog.trace.instrumentation.feign;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.feign.FeignClientDecorator.DECORATE;
import static datadog.trace.instrumentation.feign.FeignClientDecorator.HTTP_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import feign.Request;
import feign.Response;
import net.bytebuddy.asm.Advice;

public class SyncClientInstrumentation implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "feign.Client";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("feign.Request")))
            .and(takesArgument(1, named("feign.Request$Options"))),
        SyncClientInstrumentation.class.getName() + "$SyncClientAdvice");
  }

  public static class SyncClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) Request request,
        @Advice.Local("ddSpan") AgentSpan span) {

      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(feign.Client.class);
      if (callDepth > 0) {
        return null;
      }

      span = startSpan(HTTP_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("ddSpan") final AgentSpan span,
        @Advice.Return final Response response,
        @Advice.Thrown final Throwable throwable) {

      CallDepthThreadLocalMap.reset(feign.Client.class);

      if (scope == null) {
        return;
      }

      try {
        if (throwable != null) {
          DECORATE.onError(span, throwable);
        } else {
          DECORATE.onResponse(span, response);
        }
        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        scope.close();
      }
    }
  }
}
