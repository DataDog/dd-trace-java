package datadog.trace.instrumentation.vertx_4_0.server;

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
import io.vertx.core.json.JsonObject;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@RequiresRequestContext(RequestContextSlot.APPSEC)
class RoutingContextJsonResponseAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  static void before(
      @Advice.Argument(0) Object source, @ActiveRequestContext RequestContext reqCtx) {

    if (source == null) {
      return;
    }

    Object object = source;
    if (object instanceof JsonObject) {
      object = ((JsonObject) object).getMap();
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.responseBody());
    if (callback == null) {
      return;
    }

    Flow<Void> flow = callback.apply(reqCtx, object);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
      if (blockResponseFunction == null) {
        return;
      }
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      blockResponseFunction.tryCommitBlockingResponse(
          reqCtx.getTraceSegment(),
          rba.getStatusCode(),
          rba.getBlockingContentType(),
          rba.getExtraHeaders());

      throw new BlockingException("Blocked request (for RoutingContext/json)");
    }
  }
}
