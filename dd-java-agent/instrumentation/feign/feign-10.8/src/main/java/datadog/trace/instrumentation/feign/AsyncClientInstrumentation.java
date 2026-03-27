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
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

public class AsyncClientInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "feign.AsyncClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("feign.Request")))
            .and(takesArgument(1, named("feign.Request$Options")))
            .and(takesArgument(2, named("java.util.Optional")))
            .and(takesArgument(3, named("java.util.function.Consumer"))),
        AsyncClientInstrumentation.class.getName() + "$AsyncClientAdvice");
  }

  public static class AsyncClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) Request request, @Advice.Local("ddSpan") AgentSpan span) {

      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(feign.AsyncClient.class);
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
        @Advice.Return(readOnly = false) CompletableFuture<Response> future,
        @Advice.Thrown final Throwable throwable) {

      // CRITICAL: CallDepthThreadLocalMap.reset() MUST be called in @OnMethodExit (same thread)
      // Do NOT call reset() inside the async completion handler (different thread)
      CallDepthThreadLocalMap.reset(feign.AsyncClient.class);

      if (scope == null) {
        return;
      }

      // Close the scope immediately since we're in async mode
      scope.close();

      if (throwable != null) {
        // Synchronous error - finish span immediately
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
        return;
      }

      // Wrap the CompletableFuture to finish the span when it completes
      // CRITICAL: Use readOnly=false and assign back to the return parameter
      future = future.whenComplete(new AsyncCompletionHandler(span));
    }
  }
}
