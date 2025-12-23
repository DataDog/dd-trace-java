package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.instrumentation.play25.appsec.BodyParserHelpers.jsValueToJavaObject;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.function.BiFunction;
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

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    if (cbp == null) {
      return;
    }
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.responseBody());
    if (callback == null) {
      return;
    }

    Flow<Void> flow = callback.apply(reqCtx, jsValueToJavaObject((JsValue) content));
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
      if (blockResponseFunction == null) {
        return;
      }
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);

      throw new BlockingException("Blocked request (for Results$Status/apply)");
    }
  }
}
