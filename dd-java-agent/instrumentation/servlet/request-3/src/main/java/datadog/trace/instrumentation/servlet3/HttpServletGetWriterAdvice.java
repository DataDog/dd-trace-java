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
import datadog.trace.instrumentation.servlet.BufferedWriterWrapper;
import java.io.PrintWriter;
import java.util.function.BiFunction;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

@SuppressWarnings("Duplicates")
@RequiresRequestContext(RequestContextSlot.APPSEC)
class HttpServletGetWriterAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.This final HttpServletResponse resp,
      @Advice.Return(readOnly = false) PrintWriter writer) {
    if (writer == null) {
      return;
    }

    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return;
    }
    String alreadyWrapped = resp.getHeader("datadog.wrapped_response_body");
    if (alreadyWrapped != null || writer instanceof BufferedWriterWrapper) {
      return;
    }
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return;
    }
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, StoredBodySupplier, Void> responseStartCb =
        cbp.getCallback(EVENTS.responseBodyStart());
    BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> responseEndedCb =
        cbp.getCallback(EVENTS.responseBodyDone());
    if (responseStartCb == null || responseEndedCb == null) {
      return;
    }

    resp.setHeader("datadog.wrapped_response_body", Boolean.TRUE.toString());

    int lengthHint = 0;
    String lengthHeader = resp.getHeader("content-length");
    if (lengthHeader != null) {
      try {
        lengthHint = Integer.parseInt(lengthHeader);
      } catch (NumberFormatException nfe) {
        // purposefully left blank
      }
    }

    StoredCharBody storedCharBody =
        new StoredCharBody(requestContext, responseStartCb, responseEndedCb, lengthHint);

    writer = new BufferedWriterWrapper(writer, storedCharBody);
  }
}
