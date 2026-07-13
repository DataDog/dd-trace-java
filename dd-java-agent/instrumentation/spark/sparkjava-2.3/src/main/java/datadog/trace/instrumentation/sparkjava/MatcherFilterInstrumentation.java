package datadog.trace.instrumentation.sparkjava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.sparkjava.SparkJavaDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

public class MatcherFilterInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "spark.webserver.MatcherFilter";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("doFilter")
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(isPublic()),
        MatcherFilterInstrumentation.class.getName() + "$MatcherFilterAdvice");
  }

  public static class MatcherFilterAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(0) final javax.servlet.ServletRequest servletRequest) {
      if (!(servletRequest instanceof HttpServletRequest)) {
        return null;
      }

      final HttpServletRequest request = (HttpServletRequest) servletRequest;

      final Object contextAttr = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
      if (contextAttr instanceof Context) {
        // Already instrumented by another HTTP server integration (e.g. Jetty)
        return null;
      }

      final Context parentContext = DECORATE.extract(request);
      final Context context = DECORATE.startSpan(request, parentContext);
      final AgentSpan span = spanFromContext(context);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request, request, parentContext);

      final ContextScope scope = context.attach();
      request.setAttribute(DD_CONTEXT_ATTRIBUTE, context);

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(0) final javax.servlet.ServletRequest servletRequest,
        @Advice.Argument(1) final javax.servlet.ServletResponse servletResponse,
        @Advice.Enter final ContextScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      final Context context = scope.context();
      final AgentSpan span = spanFromContext(context);

      if (servletResponse instanceof HttpServletResponse) {
        DECORATE.onResponse(span, (HttpServletResponse) servletResponse);
      }
      if (throwable != null) {
        DECORATE.onError(span, throwable);
      }
      DECORATE.beforeFinish(context);
      scope.close();
      span.finish();

      if (servletRequest instanceof HttpServletRequest) {
        ((HttpServletRequest) servletRequest).removeAttribute(DD_CONTEXT_ATTRIBUTE);
      }
    }
  }
}
