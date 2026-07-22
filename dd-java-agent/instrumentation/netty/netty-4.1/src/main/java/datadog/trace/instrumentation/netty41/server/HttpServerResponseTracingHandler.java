package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final boolean isResponse = msg instanceof HttpResponse;
    if (!isResponse && !(msg instanceof LastHttpContent)) {
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

    try (final ContextScope scope = storedContext.attach()) {
      final HttpResponse response = isResponse ? (HttpResponse) msg : null;
      final boolean headerOnly = response != null && isHeaderOnly(response, serverContext);

      try {
        ctx.write(msg, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        removeServerContext(ctx, serverContext);
        throw throwable;
      }
      final boolean responseComplete;
      if (response == null) {
        responseComplete = true;
      } else {
        final boolean isWebsocketUpgrade =
            response.status() == HttpResponseStatus.SWITCHING_PROTOCOLS
                && "websocket".equals(response.headers().get(HttpHeaderNames.UPGRADE));
        if (isWebsocketUpgrade) {
          ctx.channel()
              .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
              .set(new HandlerContext.Sender(span, ctx.channel().id().asShortText()));
        }
        if (isInformational(response) && !isWebsocketUpgrade) {
          return;
        }
        if (serverContext != null) {
          serverContext.markResponseStarted();
        }
        DECORATE.onResponse(span, response);
        responseComplete = isWebsocketUpgrade || msg instanceof FullHttpResponse || headerOnly;
      }
      if (responseComplete) {
        DECORATE.beforeFinish(scope.context());
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        removeServerContext(ctx, serverContext);
        final ServerRequestContext nextResponse = ServerRequestContext.nextResponse(ctx.channel());
        if (headerOnly && !(msg instanceof FullHttpResponse) && nextResponse != null) {
          ctx.write(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER), ctx.voidPromise());
        }
        BlockingResponseHandler.maybeWriteDeferredBlockResponse(ctx, nextResponse);
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

  private static boolean isHeaderOnly(
      final HttpResponse response, final ServerRequestContext serverContext) {
    final int statusCode = response.status().code();
    return (serverContext != null && serverContext.isHeadRequest())
        || statusCode == HttpResponseStatus.NO_CONTENT.code()
        || statusCode == HttpResponseStatus.RESET_CONTENT.code()
        || statusCode == HttpResponseStatus.NOT_MODIFIED.code()
        || (hasZeroContentLength(response) && !HttpUtil.isTransferEncodingChunked(response));
  }

  private static boolean isInformational(final HttpResponse response) {
    final int statusCode = response.status().code();
    return statusCode >= 100 && statusCode < 200;
  }

  private static boolean hasZeroContentLength(final HttpResponse response) {
    if (!HttpUtil.isContentLengthSet(response)) {
      return false;
    }
    try {
      return HttpUtil.getContentLength(response) == 0;
    } catch (final NumberFormatException ignored) {
      return false;
    }
  }
}
