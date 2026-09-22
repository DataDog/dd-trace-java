package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.instrumentation.play25.appsec.BodyParserHelpers.jsValueToJavaObject;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import net.bytebuddy.asm.Advice;
import play.api.libs.json.JsValue;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class ResultsStatusApplyAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void before(
      @Advice.Argument(0) final Object content, @ActiveRequestContext final RequestContext reqCtx) {

    if (!(content instanceof JsValue)) {
      return;
    }

    BodyParserHelpers.handleResponseBody(
        reqCtx, jsValueToJavaObject((JsValue) content), "Results$Status/apply");
  }
}
