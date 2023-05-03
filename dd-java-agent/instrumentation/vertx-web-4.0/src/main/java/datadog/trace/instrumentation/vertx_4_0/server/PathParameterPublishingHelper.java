package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.function.BiFunction;

public class PathParameterPublishingHelper {
  public static void publishParams(Map<String, String> params) {
    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return;
    }

    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return;
    }

    { // appsec
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestPathParams());
      if (callback != null) {
        callback.apply(requestContext, params);
      }
    }

    { // iast
      Object iastRequestContext = requestContext.getData(RequestContextSlot.IAST);
      if (iastRequestContext != null) {
        WebModule module = InstrumentationBridge.WEB;
        if (module != null) {
          for (Map.Entry<String, String> e : params.entrySet()) {
            String parameterName = e.getKey();
            String value = e.getValue();
            if (parameterName == null || value == null) {
              continue; // should not happen
            }
            module.onRequestPathParameter(parameterName, value, iastRequestContext);
          }
        }
      }
    }
  }
}
