package datadog.trace.instrumentation.springweb6;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
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
  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  @Source(SourceTypes.REQUEST_PATH_PARAMETER)
  public static void after(
      @Advice.Argument(0) final HttpServletRequest req,
      @Advice.Thrown(readOnly = false) Throwable t) {
    if (t != null) {
      return;
    }
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
          Flow<Void> flow = callback.apply(reqCtx, map);
          Flow.Action action = flow.getAction();
          if (action instanceof Flow.Action.RequestBlockingAction) {
            Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
            BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
            if (brf != null) {
              brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
            }
            t =
                new BlockingException(
                    "Blocked request (for UriTemplateVariablesHandlerInterceptor/preHandle)");
          }
        }
      }
    }

    { // iast
      IastContext iastRequestContext = reqCtx.getData(RequestContextSlot.IAST);
      if (iastRequestContext != null) {
        PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          for (Map.Entry<String, String> e : map.entrySet()) {
            String parameterName = e.getKey();
            String value = e.getValue();
            if (parameterName == null || value == null) {
              continue; // should not happen
            }
            module.taintString(
                iastRequestContext, value, SourceTypes.REQUEST_PATH_PARAMETER, parameterName);
          }
        }
      }
    }
  }
}
