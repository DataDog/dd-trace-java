package datadog.trace.instrumentation.ratpack;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.path.PathTokens;

public class PathBindingPublishingHandler implements Handler {
  public static final Handler INSTANCE = new PathBindingPublishingHandler();

  @Override
  public void handle(Context ctx) {
    try {
      maybePublishTokens(ctx);
    } finally {
      ctx.next();
    }
  }

  private void maybePublishTokens(Context ctx) {
    PathTokens tokens = ctx.getPathTokens();

    if (tokens == null || tokens.isEmpty()) {
      return;
    }

    AgentSpan agentSpan = activeSpan();
    if (agentSpan == null) {
      return;
    }

    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestPathParams());
    RequestContext requestContext = agentSpan.getRequestContext();
    if (requestContext == null || callback == null) {
      return;
    }

    callback.apply(requestContext, tokens);
  }
}
