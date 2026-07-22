package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.HTTP2_STREAM_CODEC_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.DefaultLastHttpContent;
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

    if (serverContext == null) {
      writeMirroredResponse(ctx, msg, prm, storedContext, span);
      return;
    }

    if (!(msg instanceof HttpResponse) && !serverContext.isResponseStarted()) {
      ctx.write(msg, prm);
      return;
    }

    try (final ContextScope ignored = storedContext.attach()) {
      final boolean terminalResponse = isTerminalResponse(ctx, span, serverContext, msg);
      final boolean writeSyntheticLastContent =
          msg instanceof HttpResponse
              && !(msg instanceof LastHttpContent)
              && isHeaderOnly((HttpResponse) msg, serverContext)
              && !isWebsocketUpgrade((HttpResponse) msg);
      final boolean knownLengthResponseComplete =
          !terminalResponse && serverContext.recordResponseBodyBytes(responseBodyBytes(msg));
      final boolean finishResponseOnWrite = terminalResponse || knownLengthResponseComplete;
      final ChannelPromise writePromise =
          finishResponseOnWrite && prm.isVoid() ? ctx.newPromise() : prm;
      try {
        if (finishResponseOnWrite) {
          removeServerContext(ctx, serverContext);
          writePromise.addListener(
              future -> finishSpan(serverContext, storedContext, span, future));
        }
        ctx.write(msg, writePromise);
        if (finishResponseOnWrite && (!writePromise.isDone() || writePromise.isSuccess())) {
          final ServerRequestContext nextResponse =
              ServerRequestContext.nextResponse(ctx.channel());
          if (writeSyntheticLastContent && nextResponse != null) {
            ctx.write(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER), ctx.voidPromise());
          }
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

  private static void writeMirroredResponse(
      final ChannelHandlerContext ctx,
      final Object msg,
      final ChannelPromise prm,
      final Context storedContext,
      final AgentSpan span) {
    if (!(msg instanceof HttpResponse)) {
      ctx.write(msg, prm);
      return;
    }

    try (final ContextScope ignored = storedContext.attach()) {
      final HttpResponse response = (HttpResponse) msg;
      final boolean isWebsocketUpgrade = isWebsocketUpgrade(response);

      try {
        ctx.write(msg, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        removeServerContext(ctx, null);
        throw throwable;
      }
      if (isInformationalResponse(response) && !isWebsocketUpgrade) {
        return;
      }
      if (isWebsocketUpgrade) {
        ctx.channel()
            .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
            .set(new HandlerContext.Sender(span, ctx.channel().id().asShortText()));
      }
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(storedContext);
      span.finish(); // Finish the span manually since finishSpanOnClose was false
      removeServerContext(ctx, null);
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
      if (isBodylessResponse(serverContext, response) || isWebsocketUpgrade) {
        return true;
      }
      final long contentLength = contentLength(response);
      if (contentLength > 0 && !HttpUtil.isTransferEncodingChunked(response)) {
        serverContext.setResponseContentLength(contentLength);
      }
      if (msg instanceof LastHttpContent
          && (hasKnownBodyLength(response) || HttpUtil.isContentLengthSet(response))) {
        return true;
      }
      // HTTP/1.x responses with neither a Content-Length nor chunked transfer-encoding are
      // delimited by the connection closing. HTTP/2 streams translated through
      // Http2StreamFrameToHttpObjectCodec are delimited by END_STREAM/LastHttpContent instead.
      if (isCloseDelimitedHttp1Response(ctx, response)) {
        serverContext.markResponseCloseDelimited();
        return false;
      }
      return msg instanceof LastHttpContent;
    }
    return serverContext.isResponseStarted()
        && !serverContext.isResponseCloseDelimited()
        && msg instanceof LastHttpContent;
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

  private static boolean isBodylessResponse(
      final ServerRequestContext serverContext, final HttpResponse response) {
    final int statusCode = response.status().code();
    return serverContext.isHeadRequest()
        || statusCode == 204
        || statusCode == 205
        || statusCode == 304
        || (serverContext.isConnectRequest() && statusCode >= 200 && statusCode < 300)
        || (contentLength(response) == 0 && !HttpUtil.isTransferEncodingChunked(response));
  }

  private static boolean hasKnownBodyLength(final HttpResponse response) {
    return contentLength(response) >= 0 || HttpUtil.isTransferEncodingChunked(response);
  }

  private static boolean isCloseDelimitedHttp1Response(
      final ChannelHandlerContext ctx, final HttpResponse response) {
    return !hasKnownBodyLength(response)
        && !Boolean.TRUE.equals(ctx.channel().attr(HTTP2_STREAM_CODEC_ATTRIBUTE_KEY).get());
  }

  private static long responseBodyBytes(final Object msg) {
    if (msg instanceof ByteBuf) {
      return ((ByteBuf) msg).readableBytes();
    }
    if (msg instanceof ByteBufHolder) {
      return ((ByteBufHolder) msg).content().readableBytes();
    }
    if (msg instanceof FileRegion) {
      return ((FileRegion) msg).count();
    }
    return 0;
  }

  /**
   * Returns the response {@code Content-Length}, or {@code -1} when it is absent or malformed. A
   * malformed value is left for Netty's encoder to reject rather than failing the write from the
   * tracing handler.
   */
  private static long contentLength(final HttpResponse response) {
    try {
      return HttpUtil.getContentLength(response, -1L);
    } catch (final NumberFormatException e) {
      return -1L;
    }
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
    if (!serverContext.isBeforeFinishCalled()) {
      serverContext.markBeforeFinishCalled();
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

  private static boolean isHeaderOnly(
      final HttpResponse response, final ServerRequestContext serverContext) {
    final int statusCode = response.status().code();
    return (serverContext != null && serverContext.isHeadRequest())
        || statusCode == HttpResponseStatus.NO_CONTENT.code()
        || statusCode == HttpResponseStatus.RESET_CONTENT.code()
        || statusCode == HttpResponseStatus.NOT_MODIFIED.code()
        || (hasZeroContentLength(response) && !HttpUtil.isTransferEncodingChunked(response));
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
