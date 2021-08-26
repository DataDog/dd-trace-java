package datadog.trace.instrumentation.googlehttpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
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
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class GoogleHttpClientInstrumentation extends Instrumenter.Tracing {
  public GoogleHttpClientInstrumentation() {
    super("google-http-client");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    // HttpRequest is a final class.  Only need to instrument it exactly
    // Note: the rest of com.google.api is ignored in AdditionalLibraryIgnoresMatcher to speed
    // things up
    return named("com.google.api.client.http.HttpRequest");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GoogleHttpClientDecorator", packageName + ".HeadersInjectAdapter"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(named("execute")).and(takesArguments(0)),
        GoogleHttpClientInstrumentation.class.getName() + "$GoogleHttpClientAdvice");

    transformation.applyAdvice(
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
        @Advice.This HttpRequest request, @Advice.Local("inherited") boolean inheritedScope) {
      TraceScope activeScope = activeScope();
      // detect if scope was propagated here by java-concurrent handling
      // of async requests
      if (activeScope instanceof AgentScope) {
        AgentScope agentScope = (AgentScope) activeScope;
        AgentSpan span = agentScope.span();
        // reference equality to check this instrumentation created the span,
        // not some other HTTP client
        if (HTTP_REQUEST == span.getOperationName()) {
          inheritedScope = true;
          return agentScope;
        }
      }
      return activateSpan(DECORATE.prepareSpan(startSpan(HTTP_REQUEST), request));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter AgentScope scope,
        @Advice.Local("inherited") boolean inheritedScope,
        @Advice.Return final HttpResponse response,
        @Advice.Thrown final Throwable throwable) {
      try {
        AgentSpan span = scope.span();
        DECORATE.onError(span, throwable);
        DECORATE.onResponse(span, response);

        DECORATE.beforeFinish(span);
        span.finish();
      } finally {
        if (!inheritedScope) {
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
