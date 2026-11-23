package datadog.trace.instrumentation.play25.appsec;

import com.fasterxml.jackson.databind.JsonNode;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import play.mvc.StatusHeader;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class StatusHeaderSendJsonAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void before(
      @Advice.Argument(0) final JsonNode json, @ActiveRequestContext final RequestContext reqCtx) {

    if (CallDepthThreadLocalMap.incrementCallDepth(StatusHeader.class) > 0) {
      return;
    }

    if (json == null) {
      return;
    }

    BodyParserHelpers.handleResponseBody(reqCtx, json, "StatusHeader/sendJson");
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  static void after() {
    CallDepthThreadLocalMap.decrementCallDepth(StatusHeader.class);
  }
}
