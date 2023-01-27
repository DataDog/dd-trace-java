package datadog.trace.instrumentation.netty40.server;

import static io.netty.handler.codec.http.HttpHeaders.setContentLength;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockingResponseHandler extends ChannelInboundHandlerAdapter {
  private final Flow.Action.RequestBlockingAction rba;

  private static final Logger LOGGER = LoggerFactory.getLogger(BlockingResponseHandler.class);
  private static volatile boolean HAS_WARNED;

  private boolean hasBlockedAlready;

  public BlockingResponseHandler(Flow.Action.RequestBlockingAction rba) {
    this.rba = rba;
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
        LOGGER.debug(
            "Unable to block because HttpServerResponseTracingHandler was not found on the pipeline");
      } else {
        LOGGER.warn(
            "Unable to block because HttpServerResponseTracingHandler was not found on the pipeline");
        HAS_WARNED = true;
      }
      ctx.fireChannelRead(msg);
      return;
    }

    HttpRequest request = (HttpRequest) msg;

    int httpCode = BlockingActionHelper.getHttpCode(rba.getStatusCode());
    HttpResponseStatus httpResponseStatus = HttpResponseStatus.valueOf(httpCode);
    FullHttpResponse response =
        new DefaultFullHttpResponse(request.getProtocolVersion(), httpResponseStatus);

    String acceptHeader = request.headers().get("accept");
    BlockingActionHelper.TemplateType type =
        BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);
    response
        .headers()
        .set("Content-type", BlockingActionHelper.getContentType(type))
        .set("Connection", "close");

    byte[] template = BlockingActionHelper.getTemplate(type);
    setContentLength(response, template.length);
    response.content().writeBytes(template);

    this.hasBlockedAlready = true;
    ReferenceCountUtil.release(msg);

    // unlike in netty 3.8, write starts in the handler before the one associated with ctx
    // so add a dummy one that will be skipped. We do not want to start from the end of the
    // pipeline because there is an increased risk of hitting duplex handlers that
    // expect to have seen a request before processing the response
    ctxForDownstream =
        ctxForDownstream
            .pipeline()
            .addAfter(ctxForDownstream.name(), "noop", new ChannelOutboundHandlerAdapter())
            .context("noop");
    ctxForDownstream
        .writeAndFlush(response)
        .addListener(
            fut -> {
              if (!fut.isSuccess()) {
                LOGGER.warn("Write of blocking response failed", fut.cause());
              }
              ctx.channel().close();
            });
  }
}
