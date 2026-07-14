package datadog.trace.instrumentation.netty41.server;

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
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final ServerRequestContext serverContext = ServerRequestContext.nextResponse(ctx.channel());
    final Context storedContext = serverContext == null ? null : serverContext.tracingContext();
    final AgentSpan span = AgentSpan.fromContext(storedContext);

    if (span == null) {
      ctx.write(msg, prm);
      return;
    }

    if (!(msg instanceof HttpResponse) && !serverContext.isResponseStarted()) {
      ctx.write(msg, prm);
      return;
    }

    try (final ContextScope ignored = storedContext.attach()) {
      final boolean terminalResponse = isTerminalResponse(ctx, span, serverContext, msg);
      final ChannelPromise writePromise = terminalResponse && prm.isVoid() ? ctx.newPromise() : prm;
      try {
        if (terminalResponse) {
          ServerRequestContext.remove(ctx.channel(), serverContext);
          writePromise.addListener(
              future -> finishSpan(serverContext, storedContext, span, future));
        }
        ctx.write(msg, writePromise);
        if (serverContext.isResponseStarted()) {
          beforeFinish(serverContext, storedContext);
        }
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        if (!terminalResponse) {
          ServerRequestContext.remove(ctx.channel(), serverContext);
        }
        finishSpan(serverContext, storedContext, span);
        throw throwable;
      }
    }
  }

  private static boolean isTerminalResponse(
      final ChannelHandlerContext ctx,
      final AgentSpan span,
      final ServerRequestContext serverContext,
      final Object msg) {
    if (msg instanceof HttpResponse) {
      final HttpResponse response = (HttpResponse) msg;

      final boolean isWebsocketUpgrade = isWebsocketUpgrade(response);
      if (isInformationalResponse(response) && !isWebsocketUpgrade) {
        return false;
      }

      if (isWebsocketUpgrade) {
        ctx.channel()
            .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
            .set(new HandlerContext.Sender(span, ctx.channel().id().asShortText()));
      }
      DECORATE.onResponse(span, response);
      serverContext.markResponseStarted();
      return msg instanceof LastHttpContent
          || isBodylessResponse(serverContext, response)
          || isWebsocketUpgrade;
    }
    return serverContext.isResponseStarted() && msg instanceof LastHttpContent;
  }

  private static boolean isInformationalResponse(final HttpResponse response) {
    final int statusCode = response.status().code();
    return statusCode >= 100 && statusCode < 200;
  }

  private static boolean isWebsocketUpgrade(final HttpResponse response) {
    return response.status() == HttpResponseStatus.SWITCHING_PROTOCOLS
        && response
            .headers()
            .containsValue(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
  }

  private static boolean isBodylessResponse(
      final ServerRequestContext serverContext, final HttpResponse response) {
    final int statusCode = response.status().code();
    return serverContext.isHeadRequest()
        || statusCode == 204
        || statusCode == 205
        || statusCode == 304
        || (serverContext.isConnectRequest() && statusCode >= 200 && statusCode < 300)
        || (HttpUtil.getContentLength(response, -1) == 0
            && !HttpUtil.isTransferEncodingChunked(response));
  }

  private static void finishSpan(
      final ServerRequestContext serverContext,
      final Context storedContext,
      final AgentSpan span,
      final Future<?> future) {
    if (!future.isSuccess()) {
      DECORATE.onError(span, future.cause());
      span.setHttpStatusCode(500);
    }
    finishSpan(serverContext, storedContext, span);
  }

  private static void finishSpan(
      final ServerRequestContext serverContext, final Context storedContext, final AgentSpan span) {
    beforeFinish(serverContext, storedContext);
    span.finish(); // Finish the span manually since finishSpanOnClose was false
  }

  private static void beforeFinish(
      final ServerRequestContext serverContext, final Context storedContext) {
    if (!serverContext.isBeforeFinishCalled()) {
      serverContext.markBeforeFinishCalled();
      DECORATE.beforeFinish(storedContext);
    }
  }
}
