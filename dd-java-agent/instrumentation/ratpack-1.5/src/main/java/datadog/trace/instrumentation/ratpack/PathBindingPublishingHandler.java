package datadog.trace.instrumentation.ratpack;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.path.PathTokens;

public class PathBindingPublishingHandler implements Handler {
  public static final Handler INSTANCE = new PathBindingPublishingHandler();
  private static final Logger LOGGER = LoggerFactory.getLogger(PathBindingPublishingHandler.class);

  @Override
  public void handle(Context ctx) {
    boolean doDelegation = true;
    try {
      doDelegation = maybePublishTokens(ctx);
    } finally {
      if (doDelegation) {
        ctx.next();
      } else {
        throw new BlockingException("Blocking request");
      }
    }
  }

  private boolean maybePublishTokens(Context ctx) {
    PathTokens tokens = ctx.getPathTokens();

    if (tokens == null || tokens.isEmpty()) {
      return true;
    }

    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return true;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestPathParams());
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null || callback == null) {
      return true;
    }

    Flow<Void> flow = callback.apply(requestContext, tokens);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      BlockResponseFunction blockResponseFunction =
          agentSpan.getRequestContext().getBlockResponseFunction();
      if (blockResponseFunction == null) {
        LOGGER.warn("Can't block path parameters (no block response function available)");
        return true;
      }
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      blockResponseFunction.tryCommitBlockingResponse(requestContext.getTraceSegment(), rba);
      return false;
    }

    return true;
  }
}
