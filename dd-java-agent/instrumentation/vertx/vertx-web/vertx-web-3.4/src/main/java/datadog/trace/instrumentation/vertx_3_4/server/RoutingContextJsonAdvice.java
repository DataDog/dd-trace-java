package datadog.trace.instrumentation.vertx_3_4.server;

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
class RoutingContextJsonAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  static void after(
      @Advice.Return Object obj_,
      @ActiveRequestContext RequestContext reqCtx,
      @Advice.Thrown(readOnly = false) Throwable throwable) {
    if (obj_ == null) {
      return;
    }
    Object obj = obj_;
    if (obj instanceof JsonObject) {
      obj = ((JsonObject) obj).getMap();
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    if (callback == null) {
      return;
    }

    Flow<Void> flow = callback.apply(reqCtx, obj);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction blockResponseFunction = reqCtx.getBlockResponseFunction();
      if (blockResponseFunction == null) {
        return;
      }
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      blockResponseFunction.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
      if (throwable == null) {
        throwable = new BlockingException("Blocked request (for RoutingContextImpl/getBodyAsJson)");
      }
    }
  }
}
