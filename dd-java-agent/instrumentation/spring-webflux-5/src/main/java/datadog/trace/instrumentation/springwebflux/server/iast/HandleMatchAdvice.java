package datadog.trace.instrumentation.springwebflux.server.iast;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;

@RequiresRequestContext(RequestContextSlot.IAST)
public class HandleMatchAdvice {

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  @Source(SourceTypes.REQUEST_PATH_PARAMETER)
  public static void after(
      @Advice.Argument(2) ServerWebExchange xchg, @ActiveRequestContext RequestContext reqCtx) {

    Object templateVars = xchg.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    Object matrixVars = xchg.getAttribute(HandlerMapping.MATRIX_VARIABLES_ATTRIBUTE);
    if (templateVars == null && matrixVars == null) {
      return;
    }

    IastContext iastRequestContext = reqCtx.getData(RequestContextSlot.IAST);

    PropagationModule module = InstrumentationBridge.PROPAGATION;
    if (module != null) {
      if (templateVars instanceof Map) {
        for (Map.Entry<String, String> e : ((Map<String, String>) templateVars).entrySet()) {
          String parameterName = e.getKey();
          String value = e.getValue();
          if (parameterName == null || value == null) {
            continue; // should not happen
          }
          module.taintString(
              iastRequestContext, value, SourceTypes.REQUEST_PATH_PARAMETER, parameterName);
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
              module.taintString(
                  iastRequestContext,
                  innerKey,
                  SourceTypes.REQUEST_MATRIX_PARAMETER,
                  parameterName);
            }
            Iterable<String> innerValues = ie.getValue();
            if (innerValues != null) {
              for (String iv : innerValues) {
                module.taintString(
                    iastRequestContext, iv, SourceTypes.REQUEST_MATRIX_PARAMETER, parameterName);
              }
            }
          }
        }
      }
    }
  }
}
