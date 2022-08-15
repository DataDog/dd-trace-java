package datadog.trace.instrumentation.ratpack;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import ratpack.form.Form;

public class ContextParseAdvice {

  // for now ignore that the parser can be configured to mix in the query string
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(@Advice.Return Object obj_) {
    Object obj = obj_;
    if (obj == null) {
      return;
    }
    if (obj instanceof Form) {
      // handled by netty
      return;
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
