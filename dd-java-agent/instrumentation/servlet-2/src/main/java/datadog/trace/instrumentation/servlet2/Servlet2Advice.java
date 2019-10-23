package datadog.trace.instrumentation.servlet2;

import static datadog.trace.agent.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.servlet2.HttpServletRequestExtractAdapter.GETTER;
import static datadog.trace.instrumentation.servlet2.Servlet2Decorator.DECORATE;

import datadog.trace.api.DDTags;
import datadog.trace.instrumentation.api.AgentScope;
import datadog.trace.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.api.AgentSpan.Context;
import io.opentracing.tag.Tags;
import java.security.Principal;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class Servlet2Advice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final Object servlet,
      @Advice.Argument(0) final ServletRequest req,
      @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC)
          ServletResponse resp) {
    final Object spanAttr = req.getAttribute(DD_SPAN_ATTRIBUTE);
    if (!(req instanceof HttpServletRequest) || spanAttr != null) {
      // Tracing might already be applied by the FilterChain.  If so ignore this.
      return null;
    }

    if (resp instanceof HttpServletResponse) {
      resp = new StatusSavingHttpServletResponseWrapper((HttpServletResponse) resp);
    }

    final HttpServletRequest httpServletRequest = (HttpServletRequest) req;
    final Context extractedContext = propagate().extract(httpServletRequest, GETTER);

    final AgentSpan span =
        startSpan("servlet.request", extractedContext)
            .setTag("span.origin.type", servlet.getClass().getName());

    DECORATE.afterStart(span);
    DECORATE.onConnection(span, httpServletRequest);
    DECORATE.onRequest(span, httpServletRequest);

    req.setAttribute(DD_SPAN_ATTRIBUTE, span);

    final AgentScope scope = activateSpan(span, true);
    scope.setAsyncPropagation(true);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Argument(0) final ServletRequest request,
      @Advice.Argument(1) final ServletResponse response,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable) {
    // Set user.principal regardless of who created this span.
    final AgentSpan currentSpan = activeSpan();
    if (currentSpan != null) {
      if (request instanceof HttpServletRequest) {
        final Principal principal = ((HttpServletRequest) request).getUserPrincipal();
        if (principal != null) {
          currentSpan.setTag(DDTags.USER_NAME, principal.getName());
        }
      }
    }

    if (scope != null) {
      final AgentSpan span = scope.span();
      DECORATE.onResponse(span, response);
      if (throwable != null) {
        if (response instanceof StatusSavingHttpServletResponseWrapper
            && ((StatusSavingHttpServletResponseWrapper) response).status
                == HttpServletResponse.SC_OK) {
          // exception was thrown but status code wasn't set
          span.setTag(Tags.HTTP_STATUS.getKey(), 500);
        }
        DECORATE.onError(span, throwable);
      }
      DECORATE.beforeFinish(span);

      scope.setAsyncPropagation(false);
      scope.close();
    }
  }
}
