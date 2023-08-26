package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_FIN_DISP_LIST_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDTags;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.servlet.ServletBlockingHelper;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

public class Servlet3Advice {

  @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
  public static boolean onEnter(
      @Advice.Argument(value = 0, readOnly = false) ServletRequest request,
      @Advice.Argument(value = 1) ServletResponse response,
      @Advice.Local("isDispatch") boolean isDispatch,
      @Advice.Local("finishSpan") boolean finishSpan,
      @Advice.Local("agentScope") AgentScope scope) {
    final boolean invalidRequest =
        !(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse);
    if (invalidRequest) {
      return false;
    }

    final HttpServletRequest httpServletRequest = (HttpServletRequest) request;
    final HttpServletResponse httpServletResponse = (HttpServletResponse) response;

    Object dispatchSpan = request.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
    if (dispatchSpan instanceof AgentSpan) {
      request.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
      isDispatch = true; // local default is false;
      // Activate the dispatch span as the request span so it can be finished with the request.
      // We don't want to create a new servlet.request span since this is internal processing.
      AgentSpan castDispatchSpan = (AgentSpan) dispatchSpan;
      // the dispatch span was already activated in Jetty's HandleAdvice. We let it finish the span
      // to avoid trying to finish twice
      finishSpan = activeSpan() != dispatchSpan;
      scope = activateSpan(castDispatchSpan);
      return false;
    }

    finishSpan = true;

    Object spanAttrValue = request.getAttribute(DD_SPAN_ATTRIBUTE);
    final boolean hasServletTrace = spanAttrValue instanceof AgentSpan;
    if (hasServletTrace) {
      // Tracing might already be applied by other instrumentation,
      // the FilterChain or a parent request (forward/include).
      return false;
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
          span.getRequestContext().getTraceSegment(), httpServletRequest, httpServletResponse, rba);
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
      @Advice.Local("isDispatch") boolean isDispatch,
      @Advice.Local("finishSpan") boolean finishSpan,
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
      final HttpServletResponse resp = (HttpServletResponse) response;

      final AgentSpan span = scope.span();

      if (request.isAsyncStarted()) {
        AtomicBoolean activated = new AtomicBoolean();
        FinishAsyncDispatchListener asyncListener =
            new FinishAsyncDispatchListener(span, activated, !isDispatch);
        // Jetty doesn't always call the listener, if the request ends before
        request.setAttribute(DD_FIN_DISP_LIST_SPAN_ATTRIBUTE, asyncListener);
        try {
          request.getAsyncContext().addListener(asyncListener);
        } catch (final IllegalStateException e) {
          // org.eclipse.jetty.server.Request may throw an exception here if request became
          // finished after check above. We just ignore that exception and move on.
        }
        if (!request.isAsyncStarted() && activated.compareAndSet(false, true)) {
          if (!isDispatch) {
            DECORATE.onResponse(span, resp);
          }
          if (finishSpan) {
            DECORATE.beforeFinish(span);
            span.finish(); // Finish the span manually since finishSpanOnClose was false
          }
        }
      } else { // not async
        // Finish the span manually since finishSpanOnClose was false
        if (throwable == null) {
          if (!isDispatch) {
            DECORATE.onResponse(span, resp);
          }
        } else { // has thrown
          if (!isDispatch) {
            // We don't want to put the status on the dispatch span.
            // (It might be wrong/different from the server span with an exception handler.)
            DECORATE.onResponse(span, resp);
            if (resp.getStatus() == HttpServletResponse.SC_OK) {
              // exception is thrown in filter chain, but status code is likely incorrect
              span.setHttpStatusCode(500);
            }
          }
          DECORATE.onError(span, throwable);
        }
        if (finishSpan) {
          DECORATE.beforeFinish(span);
          span.finish(); // Finish the span manually since finishSpanOnClose was false
        }
      }
    }
    scope.close();
  }
}
