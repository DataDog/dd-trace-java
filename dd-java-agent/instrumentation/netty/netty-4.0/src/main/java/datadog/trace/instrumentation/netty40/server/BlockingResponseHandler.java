package datadog.trace.instrumentation.netty40.server;

import static io.netty.handler.codec.http.HttpHeaders.setContentLength;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
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
import io.netty.util.ReferenceCountUtil;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockingResponseHandler extends ChannelInboundHandlerAdapter {
  public static final Logger log = LoggerFactory.getLogger(BlockingResponseHandler.class);
  private static volatile boolean HAS_WARNED;

  private final int statusCode;
  private final BlockingContentType bct;
  private final Map<String, String> extraHeaders;
  private final TraceSegment segment;

  private boolean hasBlockedAlready;

  public BlockingResponseHandler(
      TraceSegment segment,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders) {
    this.segment = segment;
    this.statusCode = statusCode;
    this.bct = bct;
    this.extraHeaders = extraHeaders;
  }

  public BlockingResponseHandler(TraceSegment segment, Flow.Action.RequestBlockingAction rba) {
    this(segment, rba.getStatusCode(), rba.getBlockingContentType(), rba.getExtraHeaders());
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
      if (HAS_WARNED) {
        log.debug(
            "Unable to block because HttpServerResponseTracingHandler was not found on the pipeline");
      } else {
        log.warn(
            "Unable to block because HttpServerResponseTracingHandler was not found on the pipeline");
        HAS_WARNED = true;
      }
      ctx.fireChannelRead(msg);
      return;
    }

    HttpRequest request = (HttpRequest) msg;

    int httpCode = BlockingActionHelper.getHttpCode(statusCode);
    HttpResponseStatus httpResponseStatus = HttpResponseStatus.valueOf(httpCode);
    FullHttpResponse response =
        new DefaultFullHttpResponse(request.getProtocolVersion(), httpResponseStatus);

    HttpHeaders headers = response.headers();
    headers.set("Connection", "close");

    for (Map.Entry<String, String> h : this.extraHeaders.entrySet()) {
      headers.set(h.getKey(), h.getValue());
    }

    if (bct != BlockingContentType.NONE) {
      String acceptHeader = request.headers().get("accept");
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(bct, acceptHeader);
      headers.set("Content-type", BlockingActionHelper.getContentType(type));

      byte[] template = BlockingActionHelper.getTemplate(type);
      setContentLength(response, template.length);
      response.content().writeBytes(template);
    }

    this.hasBlockedAlready = true;
    ReferenceCountUtil.release(msg);

    // write starts in the handler before the one associated with ctx
    // so add one that will be skipped (but that will prevent any writes later coming from later
    // handlers).
    // We do not want to start from the end of the
    // pipeline because there is an increased risk of hitting duplex handlers that
    // expect to have seen a request before processing the response
    ctxForDownstream =
        ctxForDownstream
            .pipeline()
            .addAfter(
                ctxForDownstream.name(),
                "ignore_all_writes_handler",
                IgnoreAllWritesHandler.INSTANCE)
            .context("ignore_all_writes_handler");

    segment.effectivelyBlocked();

    ctxForDownstream
        .writeAndFlush(response)
        .addListener(
            fut -> {
              if (!fut.isSuccess()) {
                log.warn("Write of blocking response failed", fut.cause());
              }
              ctx.channel().close();
            });
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
