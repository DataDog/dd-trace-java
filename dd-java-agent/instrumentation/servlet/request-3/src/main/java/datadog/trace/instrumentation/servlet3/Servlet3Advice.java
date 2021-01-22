package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator._500;
import static datadog.trace.instrumentation.servlet3.HttpServletRequestExtractAdapter.GETTER;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DECORATE;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.SERVLET_REQUEST;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDTags;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

public class Servlet3Advice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.Argument(0) final ServletRequest request,
      @Advice.Local("isDispatch") boolean isDispatch) {
    final boolean invalidRequest = !(request instanceof HttpServletRequest);
    if (invalidRequest) {
      return null;
    }

    final HttpServletRequest httpServletRequest = (HttpServletRequest) request;

    Object dispatchSpan = request.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
    if (dispatchSpan instanceof AgentSpan) {
      request.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
      isDispatch = true; // local default is false;
      // Activate the dispatch span as the request span so it can be finished with the request.
      // We don't want to create a new servlet.request span since this is internal processing.
      return activateSpan(((AgentSpan) dispatchSpan));
    }

    final boolean hasServletTrace = request.getAttribute(DD_SPAN_ATTRIBUTE) instanceof AgentSpan;
    if (hasServletTrace) {
      // Tracing might already be applied by other instrumentation,
      // the FilterChain or a parent request (forward/include).
      return null;
    }

    final AgentSpan.Context extractedContext = propagate().extract(httpServletRequest, GETTER);

    final AgentSpan span = startSpan(SERVLET_REQUEST, extractedContext).setMeasured(true);

    DECORATE.afterStart(span);
    DECORATE.onConnection(span, httpServletRequest);
    DECORATE.onRequest(span, httpServletRequest);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);

    httpServletRequest.setAttribute(DD_SPAN_ATTRIBUTE, span);
    httpServletRequest.setAttribute(
        CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
    httpServletRequest.setAttribute(
        CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) final ServletRequest request,
      @Advice.Argument(1) final ServletResponse response,
      @Advice.Enter final AgentScope scope,
      @Advice.Local("isDispatch") boolean isDispatch,
      @Advice.Thrown final Throwable throwable) {
    // Set user.principal regardless of who created this span.
    final Object spanAttr = request.getAttribute(DD_SPAN_ATTRIBUTE);
    if (Config.get().isServletPrincipalEnabled()
        && spanAttr instanceof AgentSpan
        && request instanceof HttpServletRequest) {
      final Principal principal = ((HttpServletRequest) request).getUserPrincipal();
      if (principal != null) {
        ((AgentSpan) spanAttr).setTag(DDTags.USER_NAME, principal.getName());
      }
    }

    if (scope == null) {
      return;
    }

    if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
      final HttpServletRequest req = (HttpServletRequest) request;
      final HttpServletResponse resp = (HttpServletResponse) response;

      final AgentSpan span = scope.span();

      if (throwable != null) {
        if (!isDispatch) {
          // We don't want to put the status on the dispatch span.
          // (It might be wrong/different from the server span with an exception handler.)
          DECORATE.onResponse(span, resp);
          if (resp.getStatus() == HttpServletResponse.SC_OK) {
            // exception is thrown in filter chain, but status code is likely incorrect
            span.setTag(Tags.HTTP_STATUS, _500);
          }
        }
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
      } else {
        final AtomicBoolean activated = new AtomicBoolean(false);
        if (req.isAsyncStarted()) {
          try {
            req.getAsyncContext()
                .addListener(new TagSettingAsyncListener(activated, span, isDispatch));
          } catch (final IllegalStateException e) {
            // org.eclipse.jetty.server.Request may throw an exception here if request became
            // finished after check above. We just ignore that exception and move on.
          }
        }
        // Check again in case the request finished before adding the listener.
        if (!req.isAsyncStarted() && activated.compareAndSet(false, true)) {
          if (!isDispatch) {
            DECORATE.onResponse(span, resp);
          }
          DECORATE.beforeFinish(span);
          span.finish(); // Finish the span manually since finishSpanOnClose was false
        }
        // else span finished in TagSettingAsyncListener
      }
      scope.close();
    }
  }
}
