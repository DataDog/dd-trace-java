package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathParameterPublishingHelper {
  private static final Logger log = LoggerFactory.getLogger(PathParameterPublishingHelper.class);

  public static Throwable publishParams(Map<String, String> params) {
    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return null;
    }

    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null) {
      return null;
    }

    { // appsec
      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestPathParams());
      if (callback != null) {
        Flow<Void> flow = callback.apply(requestContext, params);
        Flow.Action action = flow.getAction();
        if (action instanceof Flow.Action.RequestBlockingAction) {
          BlockResponseFunction brf = requestContext.getBlockResponseFunction();
          if (brf == null) {
            log.warn("Can't block. Don't know how to block on this server");
          } else {
            Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
            brf.tryCommitBlockingResponse(
                requestContext.getTraceSegment(),
                rba.getStatusCode(),
                rba.getBlockingContentType(),
                rba.getExtraHeaders());

            return new BlockingException("Blocked request (for route/matches)");
          }
        }
      }
    }

    { // iast
      IastContext iastRequestContext = requestContext.getData(RequestContextSlot.IAST);
      if (iastRequestContext != null) {
        PropagationModule module = InstrumentationBridge.PROPAGATION;
        if (module != null) {
          for (Map.Entry<String, String> e : params.entrySet()) {
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

    return null;
  }
}
