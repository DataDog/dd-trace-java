package datadog.trace.instrumentation.servlet2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet2.Servlet2Decorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDTags;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.servlet.ServletBlockingHelper;
import java.security.Principal;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class Servlet2Advice {

  @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
  public static boolean onEnter(
      @Advice.This final Object servlet,
      @Advice.Argument(value = 0, readOnly = false) ServletRequest request,
      @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) final ServletResponse response,
      @Advice.Local("agentScope") AgentScope scope) {

    final boolean invalidRequest = !(request instanceof HttpServletRequest);
    if (invalidRequest) {
      return false;
    }

    final HttpServletRequest httpServletRequest = (HttpServletRequest) request;
    Object spanAttr = request.getAttribute(DD_SPAN_ATTRIBUTE);
    final boolean hasServletTrace = spanAttr instanceof AgentSpan;
    if (hasServletTrace) {
      // Tracing might already be applied by the FilterChain or a parent request (forward/include).
      return false;
    }

    if (response instanceof HttpServletResponse) {
      // Default value for checking for uncaught error later
      InstrumentationContext.get(ServletResponse.class, Integer.class).put(response, 200);
    }

    final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(httpServletRequest);
    final AgentSpan span = DECORATE.startSpan(httpServletRequest, extractedContext);
    scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, httpServletRequest, httpServletRequest, extractedContext);

    httpServletRequest.setAttribute(DD_SPAN_ATTRIBUTE, span);
    httpServletRequest.setAttribute(
        CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
    httpServletRequest.setAttribute(
        CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());

    Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
    if (rba != null) {
      ServletBlockingHelper.commitBlockingResponse(
          span.getRequestContext().getTraceSegment(),
          httpServletRequest,
          (HttpServletResponse) response,
          rba);
      span.getRequestContext().getTraceSegment().effectivelyBlocked();
      return true; // skip method body
    }

    return false;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) final ServletRequest request,
      @Advice.Argument(1) final ServletResponse response,
      @Advice.Local("agentScope") final AgentScope scope,
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
    final AgentSpan span = scope.span();

    if (response instanceof HttpServletResponse) {
      DECORATE.onResponse(
          span, InstrumentationContext.get(ServletResponse.class, Integer.class).get(response));
    } else {
      DECORATE.onResponse(span, null);
    }

    if (throwable != null) {
      if (response instanceof HttpServletResponse
          && InstrumentationContext.get(ServletResponse.class, Integer.class).get(response)
              == HttpServletResponse.SC_OK) {
        // exception was thrown but status code wasn't set
        span.setHttpStatusCode(500);
      }
      DECORATE.onError(span, throwable);
    }
    DECORATE.beforeFinish(span);

    scope.setAsyncPropagation(false);
    scope.close();
    span.finish();
  }
}
