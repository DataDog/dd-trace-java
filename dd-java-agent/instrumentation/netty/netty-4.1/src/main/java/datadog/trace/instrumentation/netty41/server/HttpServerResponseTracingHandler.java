package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    if (!(msg instanceof HttpResponse)) {
      ctx.write(msg, prm);
      return;
    }

    final ServerRequestContext serverContext = ServerRequestContext.nextResponse(ctx.channel());
    final Context storedContext =
        serverContext == null
            // HTTP/2 multiplex stream channels only inherit the mirrored context attribute from
            // Http2MultiplexHandlerStreamChannelInstrumentation.PropagateContextAdvice, without a
            // per-stream request queue.
            ? ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get()
            : serverContext.tracingContext();
    final AgentSpan span = AgentSpan.fromContext(storedContext);

    if (span == null) {
      ctx.write(msg, prm);
      return;
    }

    try (final ContextScope scope = storedContext.attach()) {
      final HttpResponse response = (HttpResponse) msg;

      try {
        ctx.write(msg, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        removeServerContext(ctx, serverContext);
        throw throwable;
      }
      final boolean isWebsocketUpgrade =
          response.status() == HttpResponseStatus.SWITCHING_PROTOCOLS
              && "websocket".equals(response.headers().get(HttpHeaderNames.UPGRADE));
      if (isWebsocketUpgrade) {
        ctx.channel()
            .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
            .set(new HandlerContext.Sender(span, ctx.channel().id().asShortText()));
      }
      if (response.status() != HttpResponseStatus.CONTINUE
          && (response.status() != HttpResponseStatus.SWITCHING_PROTOCOLS || isWebsocketUpgrade)) {
        DECORATE.onResponse(span, response);
        DECORATE.beforeFinish(scope.context());
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        removeServerContext(ctx, serverContext);
      }
    }
  }

  private static void removeServerContext(
      final ChannelHandlerContext ctx, final ServerRequestContext serverContext) {
    if (serverContext == null) {
      ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
    } else {
      ServerRequestContext.remove(ctx.channel(), serverContext);
    }
  }
}
