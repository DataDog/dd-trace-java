package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import net.bytebuddy.asm.Advice;

class RequestDecodingEndAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(@Advice.This HttpServerRequest request) {
    MultiMap entries = request.formAttributes();
    if (entries.isEmpty()) {
      return;
    }

    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().instrumentationGateway();
    BiFunction<RequestContext<Object>, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    RequestContext<Object> requestContext = agentSpan.getRequestContext();
    if (requestContext == null || callback == null) {
      return;
    }

    callback.apply(requestContext, new MultiMapAsMap(entries));
  }
}
