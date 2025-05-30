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
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;

@SuppressWarnings("Duplicates")
@RequiresRequestContext(RequestContextSlot.APPSEC)
class HttpServletGetOutputStreamAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(
      @Advice.This final HttpServletResponse resp,
      @Advice.Return(readOnly = false) ServletOutputStream os,
      @ActiveRequestContext RequestContext reqCtx) {
    if (os == null) {
      return;
    }

    String alreadyWrapped = resp.getHeader("datadog.wrapped_response_body");
    if (alreadyWrapped != null || os instanceof Servlet31OutputStreamWrapper) {
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

    String encoding = resp.getCharacterEncoding();
    Charset charset = null;
    try {
      if (encoding != null) {
        charset = Charset.forName(encoding);
      }
    } catch (IllegalArgumentException iae) {
      // purposefully left blank
    }

    StoredByteBody storedByteBody =
        new StoredByteBody(reqCtx, responseStartCb, responseEndedCb, charset, lengthHint);

    os = new Servlet31OutputStreamWrapper(os, storedByteBody);
  }
}
