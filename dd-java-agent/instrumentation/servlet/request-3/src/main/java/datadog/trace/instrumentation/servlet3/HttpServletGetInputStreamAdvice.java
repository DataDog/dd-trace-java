package datadog.trace.instrumentation.servlet3;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.http.StoredByteBody;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.nio.charset.Charset;
import java.util.function.BiFunction;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

@SuppressWarnings("Duplicates")
@RequiresRequestContext(RequestContextSlot.APPSEC)
class HttpServletGetInputStreamAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.This final HttpServletRequest req,
      @Advice.Return(readOnly = false) ServletInputStream is,
      @ActiveRequestContext RequestContext reqCtx) {
    if (is == null) {
      return;
    }

    Object alreadyWrapped = req.getAttribute("datadog.wrapped_request_body");
    if (alreadyWrapped != null || is instanceof Servlet31InputStreamWrapper) {
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

    String encoding = req.getCharacterEncoding();
    Charset charset = null;
    try {
      if (encoding != null) {
        charset = Charset.forName(encoding);
      }
    } catch (IllegalArgumentException iae) {
      // purposefully left blank
    }

    StoredByteBody storedByteBody =
        new StoredByteBody(reqCtx, requestStartCb, requestEndedCb, charset, lengthHint);

    is = new Servlet31InputStreamWrapper(is, storedByteBody);
  }
}
