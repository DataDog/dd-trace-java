package datadog.trace.instrumentation.netty41.server;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockingResponseHandler extends ChannelInboundHandlerAdapter {
  public static final Logger log = LoggerFactory.getLogger(BlockingResponseHandler.class);
  static final String BEFORE_BLOCKING_HANDLER_NAME = "before_blocking_handler";
  static final String HANDLER_NAME = "blocking_handler";
  private static final String IGNORE_ALL_WRITES_HANDLER = "ignore_all_writes_handler";
  private static final String MISSING_RESPONSE_TRACING_HANDLER_MESSAGE =
      "Unable to block because HttpServerResponseTracingHandler was not found on the pipeline";
  private static volatile boolean HAS_WARNED;

  private final TraceSegment segment;
  private final int statusCode;
  private final BlockingContentType bct;
  private final Map<String, String> extraHeaders;
  private final String securityResponseId;
  private final ServerRequestContext serverContext;

  private boolean hasBlockedAlready;

  public BlockingResponseHandler(
      TraceSegment segment,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId,
      ServerRequestContext serverContext) {
    this.segment = segment;
    this.statusCode = statusCode;
    this.bct = bct;
    this.extraHeaders = extraHeaders;
    this.securityResponseId = securityResponseId;
    this.serverContext = serverContext;
  }

  public BlockingResponseHandler(
      TraceSegment segment,
      Flow.Action.RequestBlockingAction rba,
      ServerRequestContext serverContext) {
    this.segment = segment;
    this.statusCode = rba.getStatusCode();
    this.bct = rba.getBlockingContentType();
    this.extraHeaders = rba.getExtraHeaders();
    this.securityResponseId = rba.getSecurityResponseId();
    this.serverContext = serverContext;
  }

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    if (hasBlockedAlready) {
      // we want to ignore any further data that the client might send
      ReferenceCountUtil.release(msg);
      return;
    }
    if (!(msg instanceof HttpRequest)) {
      ctx.fireChannelRead(msg);
      return;
    }

    ChannelHandlerContext ctxForDownstream =
        ctx.pipeline().context(HttpServerResponseTracingHandler.class);
    if (ctxForDownstream == null) {
      ctxForDownstream = ctx.pipeline().context(HttpServerTracingHandler.class);
    }

    if (ctxForDownstream == null) {
      logMissingResponseTracingHandler();
      // Do not let a failed block intercept later requests on this keep-alive connection.
      if (ctx.pipeline().get(BEFORE_BLOCKING_HANDLER_NAME) != null) {
        ctx.pipeline().remove(BEFORE_BLOCKING_HANDLER_NAME);
      }
      ctx.pipeline().remove(this);
      ctx.fireChannelRead(msg);
      return;
    }

    HttpRequest request = (HttpRequest) msg;

    this.hasBlockedAlready = true;
    ServerRequestContext.markRequestBlocked(ctx.channel());

    PendingBlockResponse pendingBlockResponse =
        new PendingBlockResponse(
            segment,
            statusCode,
            bct,
            extraHeaders,
            securityResponseId,
            request.protocolVersion(),
            request.headers().get("accept"));
    ReferenceCountUtil.release(msg);

    if (serverContext != null
        && ServerRequestContext.nextResponse(ctx.channel()) != serverContext) {
      serverContext.deferBlockResponse(pendingBlockResponse);
      return;
    }

    writeBlockResponse(ctxForDownstream, pendingBlockResponse);
  }

  private static void logMissingResponseTracingHandler() {
    if (HAS_WARNED) {
      log.debug(MISSING_RESPONSE_TRACING_HANDLER_MESSAGE);
    } else {
      log.warn(MISSING_RESPONSE_TRACING_HANDLER_MESSAGE);
      HAS_WARNED = true;
    }
  }

  static boolean maybeWriteDeferredBlockResponse(
      ChannelHandlerContext ctx, ServerRequestContext serverContext) {
    if (serverContext == null) {
      return false;
    }
    Object deferredBlockResponse = serverContext.deferredBlockResponse();
    if (!(deferredBlockResponse instanceof PendingBlockResponse)) {
      return false;
    }
    serverContext.deferBlockResponse(null);
    writeBlockResponse(ctx, (PendingBlockResponse) deferredBlockResponse);
    return true;
  }

  private static void writeBlockResponse(
      ChannelHandlerContext ctxForDownstream, PendingBlockResponse pendingBlockResponse) {
    // write starts in the handler before the one associated with ctx
    // so add one that will be skipped (but that will prevent any writes later coming from later
    // handlers).
    // We do not want to start from the end of the
    // pipeline because there is an increased risk of hitting duplex handlers that
    // expect to have seen a request before processing the response
    if (ctxForDownstream.pipeline().get(IGNORE_ALL_WRITES_HANDLER) == null) {
      ctxForDownstream
          .pipeline()
          .addAfter(
              ctxForDownstream.name(), IGNORE_ALL_WRITES_HANDLER, IgnoreAllWritesHandler.INSTANCE);
    }
    ChannelHandlerContext writeContext =
        ctxForDownstream.pipeline().context(IGNORE_ALL_WRITES_HANDLER);

    writeContext
        .writeAndFlush(pendingBlockResponse.toResponse())
        .addListener(
            fut -> {
              if (!fut.isSuccess()) {
                log.warn("Write of blocking response failed", fut.cause());
              }
              writeContext.channel().close();
            });
  }

  private static class PendingBlockResponse {
    private final TraceSegment segment;
    private final int statusCode;
    private final BlockingContentType bct;
    private final Map<String, String> extraHeaders;
    private final String securityResponseId;
    private final HttpVersion protocolVersion;
    private final String acceptHeader;

    // Prevent the generation of BlockingResponseHandler$1 by making this constructor
    // package-private to allow the BlockingResponseHandler to call this. This module emits Java 8
    // bytecode, so it cannot use Java 11 nested access.
    PendingBlockResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType bct,
        Map<String, String> extraHeaders,
        String securityResponseId,
        HttpVersion protocolVersion,
        String acceptHeader) {
      this.segment = segment;
      this.statusCode = statusCode;
      this.bct = bct;
      this.extraHeaders = extraHeaders;
      this.securityResponseId = securityResponseId;
      this.protocolVersion = protocolVersion;
      this.acceptHeader = acceptHeader;
    }

    FullHttpResponse toResponse() {
      int httpCode = BlockingActionHelper.getHttpCode(statusCode);
      HttpResponseStatus httpResponseStatus = HttpResponseStatus.valueOf(httpCode);
      FullHttpResponse response = new DefaultFullHttpResponse(protocolVersion, httpResponseStatus);

      HttpHeaders headers = response.headers();
      headers.set("Connection", "close");

      for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
        headers.set(h.getKey(), h.getValue());
      }

      if (bct != BlockingContentType.NONE) {
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(bct, acceptHeader);
        headers.set("Content-type", BlockingActionHelper.getContentType(type));

        byte[] template = BlockingActionHelper.getTemplate(type, securityResponseId);
        HttpUtil.setContentLength(response, template.length);
        response.content().writeBytes(template);
      }
      segment.effectivelyBlocked();
      return response;
    }
  }

  @ChannelHandler.Sharable
  public static class IgnoreAllWritesHandler extends ChannelOutboundHandlerAdapter {
    public static final IgnoreAllWritesHandler INSTANCE = new IgnoreAllWritesHandler();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      log.info("Ignored write of object {}", msg);
      ReferenceCountUtil.release(msg);
    }
  }
}
