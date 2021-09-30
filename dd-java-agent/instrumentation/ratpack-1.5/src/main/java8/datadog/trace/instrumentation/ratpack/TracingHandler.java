package datadog.trace.instrumentation.ratpack;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.ratpack.RatpackServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import ratpack.handling.Context;
import ratpack.handling.Handler;
import ratpack.http.Request;

public final class TracingHandler implements Handler {
  public static Handler INSTANCE = new TracingHandler();

  /** This constant must stay in sync with datadog.trace.instrumentation.netty41.AttributeKeys. */
  public static final AttributeKey<AgentSpan> SERVER_ATTRIBUTE_KEY =
      AttributeKey.valueOf(DD_SPAN_ATTRIBUTE);

  @Override
  public void handle(final Context ctx) {
    final Request request = ctx.getRequest();

    final Attribute<AgentSpan> spanAttribute =
        ctx.getDirectChannelAccess().getChannel().attr(SERVER_ATTRIBUTE_KEY);
    final AgentSpan nettySpan = spanAttribute.get();

    // Relying on executor instrumentation to assume the netty span is in context as the parent.
    final AgentSpan ratpackSpan = startSpan(DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(ratpackSpan);
    DECORATE.onRequest(ratpackSpan, request, request, null);
    ctx.getExecution().add(ratpackSpan);

    try (final AgentScope scope = activateSpan(ratpackSpan)) {
      scope.setAsyncPropagation(true);

      ctx.getResponse()
          .beforeSend(
              response -> {
                try (final AgentScope ignored = activateSpan(ratpackSpan)) {
                  if (nettySpan != null) {
                    // Rename the netty span resource name with the ratpack route.
                    DECORATE.onContext(nettySpan, ctx);
                  }
                  DECORATE.onResponse(ratpackSpan, response);
                  DECORATE.onContext(ratpackSpan, ctx);
                  DECORATE.beforeFinish(ratpackSpan);
                  ratpackSpan.finish();
                }
              });

      ctx.next();
    } catch (final Throwable e) {
      DECORATE.onError(ratpackSpan, e);
      DECORATE.beforeFinish(ratpackSpan);
      // finish since the callback probably didn't get added.
      ratpackSpan.finish();
      throw e;
    }
  }
}
