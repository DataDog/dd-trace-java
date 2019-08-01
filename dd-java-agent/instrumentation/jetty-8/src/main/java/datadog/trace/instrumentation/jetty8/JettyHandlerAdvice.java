package datadog.trace.instrumentation.jetty8;

import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.agent.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jetty8.HttpServletRequestGetter.GETTER;
import static datadog.trace.instrumentation.jetty8.JettyDecorator.DECORATE;

import datadog.trace.api.DDTags;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

public class JettyHandlerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final Object source,
      @Advice.Argument(2) final HttpServletRequest httpServletRequest) {

    if (req.getAttribute(DD_SPAN_ATTRIBUTE) != null) {
      // Request already being traced elsewhere.
      return null;
    }

    final AgentSpan.Context extractedContext = propagate().extract(httpServletRequest, GETTER);

    final AgentSpan span = startSpan(DECORATE, extractedContext);
    DECORATE.onConnection(span, httpServletRequest);
    DECORATE.onRequest(span, httpServletRequest);

    final String resourceName = httpServletRequest.getMethod() + " " + source.getClass().getName();
    span.setMetadata(DDTags.RESOURCE_NAME, resourceName);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    req.setAttribute(DD_SPAN_ATTRIBUTE, span);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(2) final HttpServletRequest req,
      @Advice.Argument(3) final HttpServletResponse resp,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable) {
    if (scope != null) {
      final AgentSpan span = scope.span();
      if (req.getUserPrincipal() != null) {
        span.setMetadata(DDTags.USER_NAME, req.getUserPrincipal().getName());
      }
      if (throwable != null) {
        DECORATE.onResponse(span, resp);
        if (resp.getStatus() == HttpServletResponse.SC_OK) {
          // exception is thrown in filter chain, but status code is incorrect
          span.setMetadata("http.status_code", 500);
        }
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
      } else {
        final AtomicBoolean activated = new AtomicBoolean(false);
        if (req.isAsyncStarted()) {
          try {
            req.getAsyncContext().addListener(new TagSettingAsyncListener(activated, span));
          } catch (final IllegalStateException e) {
            // org.eclipse.jetty.server.Request may throw an exception here if request became
            // finished after check above. We just ignore that exception and move on.
          }
        }
        // Check again in case the request finished before adding the listener.
        if (!req.isAsyncStarted() && activated.compareAndSet(false, true)) {
          DECORATE.onResponse(span, resp);
          DECORATE.beforeFinish(span);
          span.finish(); // Finish the span manually since finishSpanOnClose was false
        }
      }
      scope.close();
    }
  }
}
