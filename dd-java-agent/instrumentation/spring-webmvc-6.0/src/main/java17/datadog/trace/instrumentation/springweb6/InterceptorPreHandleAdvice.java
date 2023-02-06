package datadog.trace.instrumentation.springweb6;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.function.BiFunction;
import net.bytebuddy.asm.Advice;

@RequiresRequestContext(RequestContextSlot.APPSEC)
public class InterceptorPreHandleAdvice {
  private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
      "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";

  @SuppressWarnings("Duplicates")
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void after(
      @Advice.Argument(0) final HttpServletRequest req,
      @ActiveRequestContext RequestContext reqCtx) {
    Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (!(templateVars instanceof Map)) {
      return;
    }

    Map<String, Object> map = (Map<String, Object>) templateVars;
    if (map.isEmpty()) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestPathParams());
    if (callback == null) {
      return;
    }
    callback.apply(reqCtx, map);
  }
}
