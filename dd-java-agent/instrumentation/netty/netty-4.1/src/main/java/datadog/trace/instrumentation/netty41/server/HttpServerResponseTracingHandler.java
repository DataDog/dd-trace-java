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
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final boolean isResponse = msg instanceof HttpResponse;
    final boolean isLastContent = msg instanceof LastHttpContent;
    if (!isResponse && !isLastContent) {
      ctx.write(msg, prm);
      return;
    }

    final ServerRequestContext serverContext = ServerRequestContext.nextResponse(ctx.channel());
    if (!isResponse && serverContext != null && !serverContext.isResponseStarted()) {
      ctx.write(msg, prm);
      return;
    }

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

    try (final ContextScope ignored = storedContext.attach()) {
      final HttpResponse response = isResponse ? (HttpResponse) msg : null;
      final boolean websocketUpgrade = response != null && isWebsocketUpgrade(response);
      final boolean informationalResponse =
          response != null && isInformationalResponse(response) && !websocketUpgrade;
      final boolean finishResponseOnWrite = isLastContent && !informationalResponse;
      final ChannelPromise writePromise =
          finishResponseOnWrite && prm.isVoid() ? ctx.newPromise() : prm;
      try {
        if (response != null && !informationalResponse) {
          onResponse(ctx, span, serverContext, response, websocketUpgrade);
        }
        if (finishResponseOnWrite) {
          removeServerContext(ctx, serverContext);
          writePromise.addListener(
              future -> finishSpan(serverContext, storedContext, span, future));
        }
        ctx.write(msg, writePromise);
        if (finishResponseOnWrite && (!writePromise.isDone() || writePromise.isSuccess())) {
          final ServerRequestContext nextResponse =
              ServerRequestContext.nextResponse(ctx.channel());
          BlockingResponseHandler.maybeWriteDeferredBlockResponse(ctx, nextResponse);
        }
      } catch (final Throwable throwable) {
        if (!finishResponseOnWrite || !writePromise.isDone()) {
          DECORATE.onError(span, throwable);
          span.setHttpStatusCode(500);
          if (!finishResponseOnWrite) {
            removeServerContext(ctx, serverContext);
          }
          finishSpan(serverContext, storedContext, span);
        }
        throw throwable;
      }
    }
  }

  private static void onResponse(
      final ChannelHandlerContext ctx,
      final AgentSpan span,
      final ServerRequestContext serverContext,
      final HttpResponse response,
      final boolean websocketUpgrade) {
    if (websocketUpgrade) {
      ctx.channel()
          .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
          .set(new HandlerContext.Sender(span, ctx.channel().id().asShortText()));
    }
    DECORATE.onResponse(span, response);
    if (serverContext != null) {
      serverContext.markResponseStarted();
    }
  }

  private static boolean isInformationalResponse(final HttpResponse response) {
    final int statusCode = response.status().code();
    return statusCode >= 100 && statusCode < 200;
  }

  private static boolean isWebsocketUpgrade(final HttpResponse response) {
    return response.status().code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()
        && response
            .headers()
            .containsValue(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
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
    try (final ContextScope ignored = storedContext.attach()) {
      beforeFinish(serverContext, storedContext);
      span.finish(); // Finish the span manually since finishSpanOnClose was false
    }
  }

  private static void beforeFinish(
      final ServerRequestContext serverContext, final Context storedContext) {
    if (serverContext == null || !serverContext.isBeforeFinishCalled()) {
      if (serverContext != null) {
        serverContext.markBeforeFinishCalled();
      }
      DECORATE.beforeFinish(storedContext);
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
