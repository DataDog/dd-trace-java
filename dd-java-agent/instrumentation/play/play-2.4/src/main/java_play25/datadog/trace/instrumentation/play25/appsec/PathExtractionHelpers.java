package datadog.trace.instrumentation.play25.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathExtractionHelpers {
  private static final Logger log = LoggerFactory.getLogger(PathExtractionHelpers.class);

  private PathExtractionHelpers() {}

  public static BlockingException callRequestPathParamsCallback(
      RequestContext reqCtx, Map<String, Object> params, String origin) {
    try {
      return doCallRequestPathParamsCallback(reqCtx, params, origin);
    } catch (Exception e) {
      log.warn("Error calling {}", origin, e);
      return null;
    }
  }

  private static BlockingException doCallRequestPathParamsCallback(
      RequestContext reqCtx, Map<String, Object> params, String origin) {
    if (params == null || params.isEmpty()) {
      return null;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestPathParams());
    if (callback == null) {
      return null;
    }

    Flow<Void> flow = callback.apply(reqCtx, params);
    Flow.Action action = flow.getAction();
    if (!(action instanceof Flow.Action.RequestBlockingAction)) {
      return null;
    }

    Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
    BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
    if (brf != null) {
      brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
    }
    return new BlockingException("Blocked request (for " + origin + ")");
  }
}
