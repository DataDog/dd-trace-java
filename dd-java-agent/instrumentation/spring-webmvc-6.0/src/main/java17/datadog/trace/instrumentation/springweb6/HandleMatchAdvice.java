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
import datadog.trace.instrumentation.springweb.PairList;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

public class HandleMatchAdvice {
  private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";
  private static final String MATRIX_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.matrixVariables";

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(@Advice.Argument(2) final HttpServletRequest req) {
    // hacky, but APM instrumentation causes the instrumented method to be called twice
    if (req.getClass()
        .getName()
        .equals("datadog.trace.instrumentation.springweb6.PathMatchingHttpServletRequestWrapper")) {
      return;
    }

    AgentSpan agentSpan = AgentTracer.activeSpan();
    if (agentSpan == null) {
      return;
    }

    Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    Object matrixVars = req.getAttribute(MATRIX_VARIABLES_ATTRIBUTE);
    if (templateVars == null && matrixVars == null) {
      return;
    }

    RequestContext reqCtx = agentSpan.getRequestContext();
    if (reqCtx == null) {
      return;
    }

    { // appsec
      Object appSecRequestContext = reqCtx.getData(RequestContextSlot.APPSEC);
      if (appSecRequestContext != null) {

        // merge the uri template and matrix variables
        Map<String, Object> map = null;
        if (templateVars instanceof Map) {
          map = (Map<String, Object>) templateVars;
        }
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
          if (callback != null) {
            callback.apply(reqCtx, map);
          }
        }
      }
    }

    { // iast
      Object iastRequestContext = reqCtx.getData(RequestContextSlot.IAST);
      if (iastRequestContext != null) {
        WebModule module = InstrumentationBridge.WEB;
        if (module != null) {
          if (templateVars instanceof Map) {
            for (Map.Entry<String, String> e : ((Map<String, String>) templateVars).entrySet()) {
              String parameterName = e.getKey();
              String value = e.getValue();
              if (parameterName == null || value == null) {
                continue; // should not happen
              }
              module.onRequestPathParameter(parameterName, value, iastRequestContext);
            }
          }

          if (matrixVars instanceof Map) {
            for (Map.Entry<String, Map<String, Iterable<String>>> e :
                ((Map<String, Map<String, Iterable<String>>>) matrixVars).entrySet()) {
              String parameterName = e.getKey();
              Map<String, Iterable<String>> value = e.getValue();
              if (parameterName == null || value == null) {
                continue;
              }

              for (Map.Entry<String, Iterable<String>> ie : value.entrySet()) {
                String innerKey = ie.getKey();
                if (innerKey != null) {
                  module.onRequestMatrixParameter(parameterName, innerKey, iastRequestContext);
                }
                Iterable<String> innerValues = ie.getValue();
                if (innerValues != null) {
                  for (String iv : innerValues) {
                    module.onRequestMatrixParameter(parameterName, iv, iastRequestContext);
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
