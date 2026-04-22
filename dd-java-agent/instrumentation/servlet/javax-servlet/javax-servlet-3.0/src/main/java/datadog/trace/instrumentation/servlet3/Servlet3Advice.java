package datadog.trace.instrumentation.servlet3;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_FIN_DISP_LIST_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RUM_INJECTED;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
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
      @Advice.Argument(value = 1, readOnly = false) ServletResponse response,
      @Advice.Local("isDispatch") boolean isDispatch,
      @Advice.Local("finishSpan") boolean finishSpan,
      @Advice.Local("contextScope") ContextScope scope,
      @Advice.Local("rumServletWrapper") RumControllableResponse rumServletWrapper) {
    final boolean invalidRequest =
        !(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse);
    if (invalidRequest) {
      return false;
    }

    final HttpServletRequest httpServletRequest = (HttpServletRequest) request;
    HttpServletResponse httpServletResponse = (HttpServletResponse) response;

    if (RumInjector.get().isEnabled()) {
      final Object maybeRumWrapper = httpServletRequest.getAttribute(DD_RUM_INJECTED);
      if (maybeRumWrapper instanceof RumControllableResponse) {
        rumServletWrapper = (RumControllableResponse) maybeRumWrapper;
      } else {
        rumServletWrapper =
            new RumHttpServletResponseWrapper(httpServletRequest, (HttpServletResponse) response);
        httpServletRequest.setAttribute(DD_RUM_INJECTED, rumServletWrapper);
        response = (ServletResponse) rumServletWrapper;
        request =
            new RumHttpServletRequestWrapper(
                httpServletRequest, (HttpServletResponse) rumServletWrapper);
      }
    }

    Object contextAttr = request.getAttribute(DD_CONTEXT_ATTRIBUTE);

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
      // If we have an existing context, create a new context with the dispatch span
      // Otherwise just attach the dispatch span
      if (contextAttr instanceof Context) {
        Context contextWithDispatchSpan = ((Context) contextAttr).with(castDispatchSpan);
        scope = contextWithDispatchSpan.attach();
      } else {
        scope = castDispatchSpan.attach();
      }
      return false;
    }

    finishSpan = true;

    if (contextAttr instanceof Context) {
      final Context existingContext = (Context) contextAttr;
      final AgentSpan span = spanFromContext(existingContext);
      if (span != null) {
        ClassloaderConfigurationOverrides.maybeEnrichSpan(span);
        // Tracing might already be applied by other instrumentation,
        // the FilterChain or a parent request (forward/include).
        return false;
      }
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
      @Advice.Local("contextScope") final ContextScope scope,
      @Advice.Local("isDispatch") boolean isDispatch,
      @Advice.Local("finishSpan") boolean finishSpan,
      @Advice.Local("rumServletWrapper") RumControllableResponse rumServletWrapper,
      @Advice.Thrown final Throwable throwable) {
    if (rumServletWrapper != null) {
      rumServletWrapper.commit();
    }
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

    if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
      final HttpServletResponse resp = (HttpServletResponse) response;

      final AgentSpan span = spanFromContext(scope.context());

      if (request.isAsyncStarted()) {
        AtomicBoolean activated = new AtomicBoolean();
        FinishAsyncDispatchListener asyncListener =
            new FinishAsyncDispatchListener(scope, activated, !isDispatch);
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
            DECORATE.beforeFinish(scope.context());
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
          DECORATE.beforeFinish(scope.context());
          span.finish(); // Finish the span manually since finishSpanOnClose was false
        }
      }
    }
    scope.close();
  }
}
