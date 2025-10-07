package datadog.trace.instrumentation.servlet3;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.http.StoredCharBody;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.servlet.BufferedReaderWrapper;
import java.io.BufferedReader;
import java.util.function.BiFunction;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

@SuppressWarnings("Duplicates")
@RequiresRequestContext(RequestContextSlot.APPSEC)
class HttpServletGetReaderAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.This final HttpServletRequest req,
      @Advice.Return(readOnly = false) BufferedReader reader) {
    if (reader == null) {
      return;
    }

    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return;
    }
    Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
    if (alreadyWrapped != null || reader instanceof BufferedReaderWrapper) {
      return;
    }
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return;
    }
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, StoredBodySupplier, Void> requestStartCb =
        cbp.getCallback(EVENTS.requestBodyStart());
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> requestEndedCb =
        cbp.getCallback(EVENTS.requestBodyDone());
    if (requestStartCb == null || requestEndedCb == null) {
      return;
    }

    req.setAttribute("datadog.wrapped_request_body", Boolean.TRUE);

    int lengthHint = 0;
    String lengthHeader = req.getHeader("content-length");
    if (lengthHeader != null) {
      try {
        lengthHint = Integer.parseInt(lengthHeader);
      } catch (NumberFormatException nfe) {
        // purposefully left blank
      }
    }

    StoredCharBody storedCharBody =
        new StoredCharBody(requestContext, requestStartCb, requestEndedCb, lengthHint);

    reader = new BufferedReaderWrapper(reader, storedCharBody);
  }
}
