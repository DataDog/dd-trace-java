package datadog.trace.instrumentation.ratpack;

import static datadog.trace.api.gateway.Events.EVENTS;

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
import ratpack.jackson.JsonRender;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class JsonRendererAdvice {

  // for now ignore that the parser can be configured to mix in the query string
  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void after(
      @Advice.Argument(1) final JsonRender render,
      @ActiveRequestContext final RequestContext reqCtx) {
    Object obj = render == null ? null : render.getObject();
    if (obj == null) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.responseBody());
    if (callback == null) {
      return;
    }
    Flow<Void> flow = callback.apply(reqCtx, obj);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        brf.tryCommitBlockingResponse(
            reqCtx.getTraceSegment(),
            rba.getStatusCode(),
            rba.getBlockingContentType(),
            rba.getExtraHeaders());

        throw new BlockingException("Blocked request (for JsonRenderer/render)");
      }
    }
  }
}
