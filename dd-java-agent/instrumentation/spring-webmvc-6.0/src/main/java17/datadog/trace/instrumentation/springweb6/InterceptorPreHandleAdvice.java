package datadog.trace.instrumentation.springweb6;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.source.WebModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

public class InterceptorPreHandleAdvice {
  private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(@Advice.Argument(0) final HttpServletRequest req) {
    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return;
    }

    Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (!(templateVars instanceof Map)) {
      return;
    }

    Map<String, String> map = (Map<String, String>) templateVars;
    if (map.isEmpty()) {
      return;
    }

    RequestContext reqCtx = agentSpan.getRequestContext();
    if (reqCtx == null) {
      return;
    }

    { // appsec
      Object appSecRequestContext = reqCtx.getData(RequestContextSlot.APPSEC);
      if (appSecRequestContext != null) {
        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
            cbp.getCallback(EVENTS.requestPathParams());
        if (callback != null) {
          callback.apply(reqCtx, map);
        }
      }
    }

    { // iast
      Object iastRequestContext = reqCtx.getData(RequestContextSlot.IAST);
      if (iastRequestContext != null) {
        WebModule module = InstrumentationBridge.WEB;
        if (module != null) {
          for (Map.Entry<String, String> e : map.entrySet()) {
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
