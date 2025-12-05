package datadog.trace.instrumentation.googlehttpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.googlehttpclient.GoogleHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.googlehttpclient.GoogleHttpClientDecorator.HTTP_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class GoogleHttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public GoogleHttpClientInstrumentation() {
    super("google-http-client");
  }

  @Override
  public String instrumentedType() {
    // HttpRequest is a final class.  Only need to instrument it exactly
    // Note: the rest of com.google.api is ignored in the additional ignores
    // of GlobalIgnoresMatcher to speed things up
    return "com.google.api.client.http.HttpRequest";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GoogleHttpClientDecorator", packageName + ".HeadersInjectAdapter"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("execute")).and(takesArguments(0)),
        GoogleHttpClientInstrumentation.class.getName() + "$GoogleHttpClientAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("executeAsync"))
            .and(takesArguments(1))
            .and(takesArgument(0, (named("java.util.concurrent.Executor")))),
        GoogleHttpClientInstrumentation.class.getName() + "$GoogleHttpClientAsyncAdvice");
  }

  public static class GoogleHttpClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.This HttpRequest request, @Advice.Local("inherited") AgentSpan inheritedSpan) {
      AgentSpan activeSpan = activeSpan();
      // detect if span was propagated here by java-concurrent handling
      // of async requests
      if (null != activeSpan) {
        // reference equality to check this instrumentation created the span,
        // not some other HTTP client
        if (HTTP_REQUEST == activeSpan.getOperationName()) {
          inheritedSpan = activeSpan;
          return null;
        }
      }
      return activateSpan(DECORATE.prepareSpan(startSpan(HTTP_REQUEST), request));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter AgentScope scope,
        @Advice.Local("inherited") AgentSpan inheritedSpan,
        @Advice.Return final HttpResponse response,
        @Advice.Thrown final Throwable throwable) {
      try {
        AgentSpan span = scope != null ? scope.span() : inheritedSpan;
        DECORATE.onError(span, throwable);
        DECORATE.onResponse(span, response);

        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        if (scope != null) {
          scope.close();
        }
      }
    }
  }

  public static class GoogleHttpClientAsyncAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.This HttpRequest request) {
      return activateSpan(DECORATE.prepareSpan(startSpan(HTTP_REQUEST), request));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter AgentScope scope, @Advice.Thrown final Throwable throwable) {
      try {
        if (throwable != null) {
          AgentSpan span = scope.span();
          DECORATE.onError(span, throwable);
          DECORATE.beforeFinish(span);
          span.finish();
        }
      } finally {
        scope.close();
      }
    }
  }
}
