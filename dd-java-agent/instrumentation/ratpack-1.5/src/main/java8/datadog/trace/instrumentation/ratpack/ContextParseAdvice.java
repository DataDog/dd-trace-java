package datadog.trace.instrumentation.ratpack;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import ratpack.form.Form;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class ContextParseAdvice {

  // for now ignore that the parser can be configured to mix in the query string
  @Advice.OnMethodExit(suppress = Throwable.class)
  static void after(@Advice.Return Object obj_, @ActiveRequestContext RequestContext reqCtx) {
    Object obj = obj_;
    if (obj == null) {
      return;
    }
    if (obj instanceof Form) {
      // handled by netty
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    if (callback == null) {
      return;
    }
    callback.apply(reqCtx, obj);
  }
}
