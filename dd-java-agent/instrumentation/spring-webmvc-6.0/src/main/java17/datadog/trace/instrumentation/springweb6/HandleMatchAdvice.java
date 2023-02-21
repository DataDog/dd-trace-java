package datadog.trace.instrumentation.springweb6;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.springweb.PairList;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class HandleMatchAdvice {
  private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";
  private static final String MATRIX_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.matrixVariables";

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(
      @Advice.Argument(2) final HttpServletRequest req,
      @ActiveRequestContext RequestContext reqCtx) {
    Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    Map<String, Object> map = null;
    if (templateVars instanceof Map) {
      map = (Map<String, Object>) templateVars;
    }

    Object matrixVars = req.getAttribute(MATRIX_VARIABLES_ATTRIBUTE);
    if (matrixVars instanceof Map) {
      if (map != null) {
        map = new HashMap<>(map);
        for (Map.Entry<String, Object> e : ((Map<String, Object>) matrixVars).entrySet()) {
          String key = e.getKey();
          Object curValue = map.get(key);
          if (curValue != null) {
            map.put(key, new PairList(curValue, e.getValue()));
          } else {
            map.put(key, e.getValue());
          }
        }
      } else {
        map = (Map<String, Object>) matrixVars;
      }
    }

    if (map != null && !map.isEmpty()) {
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestPathParams());
      if (callback == null) {
        return;
      }
      callback.apply(reqCtx, map);
    }
  }
}
