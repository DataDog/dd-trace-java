package datadog.trace.instrumentation.servlet2;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet2.Servlet2Decorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.InstrumentationContext;
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
      @Advice.Local("contextScope") ContextScope scope) {

    final boolean invalidRequest = !(request instanceof HttpServletRequest);
    if (invalidRequest) {
      return false;
    }

    final HttpServletRequest httpServletRequest = (HttpServletRequest) request;
    Object contextAttr = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (contextAttr instanceof Context) {
      final Context existingContext = (Context) contextAttr;
      final AgentSpan span = spanFromContext(existingContext);
      if (span != null) {
        ClassloaderConfigurationOverrides.maybeEnrichSpan(span);
        // Tracing might already be applied by the FilterChain or a parent request
        // (forward/include).
        return false;
      }
    }

    if (response instanceof HttpServletResponse) {
      // Default value for checking for uncaught error later
      InstrumentationContext.get(ServletResponse.class, Integer.class).put(response, 200);
    }

    final Context parentContext = DECORATE.extract(httpServletRequest);
    final Context context = DECORATE.startSpan(httpServletRequest, parentContext);
    scope = context.attach();
    final AgentSpan span = spanFromContext(context);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, httpServletRequest, httpServletRequest, parentContext);

    httpServletRequest.setAttribute(DD_CONTEXT_ATTRIBUTE, context);
    httpServletRequest.setAttribute(
        CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
    httpServletRequest.setAttribute(
        CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());

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
      @Advice.Local("contextScope") final ContextScope scope,
      @Advice.Thrown final Throwable throwable) {
    // Set user.principal regardless of who created this span.
    final Object contextAttr = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (Config.get().isServletPrincipalEnabled()
        && contextAttr instanceof Context
        && request instanceof HttpServletRequest) {
      final Context context = (Context) contextAttr;
      final AgentSpan span = spanFromContext(context);
      if (span != null) {
        final Principal principal = ((HttpServletRequest) request).getUserPrincipal();
        if (principal != null) {
          span.setTag(DDTags.USER_NAME, principal.getName());
        }
      }
    }

    if (scope == null) {
      return;
    }
    final AgentSpan span = spanFromContext(scope.context());

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
    DECORATE.beforeFinish(scope.context());

    scope.close();
    span.finish();
  }
}
