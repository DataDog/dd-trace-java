package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.core.json.JsonObject;
import net.bytebuddy.asm.Advice;

class RoutingContextJsonAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(@Advice.Return Object obj_) {
    if (obj_ == null) {
      return;
    }
    Object obj = obj_;
    if (obj instanceof JsonObject) {
      obj = ((JsonObject) obj).getMap();
    }

    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return;
    }
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null || callback == null) {
      return;
    }

    callback.apply(requestContext, obj);
  }
}
