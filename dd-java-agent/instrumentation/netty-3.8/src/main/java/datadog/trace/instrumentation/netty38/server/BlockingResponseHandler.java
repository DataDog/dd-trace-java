package datadog.trace.instrumentation.netty38.server;

import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.util.Map;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlockingResponseHandler extends SimpleChannelUpstreamHandler {
  private final TraceSegment segment;
  private final int statusCode;
  private final BlockingContentType bct;
  private final Map<String, String> extraHeaders;

  private static final Logger log = LoggerFactory.getLogger(BlockingResponseHandler.class);
  private static volatile boolean HAS_WARNED;

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
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    if (hasBlockedAlready) {
      // we want to ignore any further data that the client might send
      return;
    }
    Object msg = e.getMessage();
    if (!(msg instanceof HttpRequest)) {
      ctx.sendUpstream(e);
      return;
    }

    // the http encoder may be placed after the decoder (and therefore this handler),
    // so when sending the downstream message, we may not reach the encoder as the message
    // is processed starting in the current point of the pipeline, not from the globally last
    // handler
    ChannelHandlerContext ctxForDownstream =
        ctx.getPipeline().getContext(HttpServerResponseTracingHandler.class);
    if (ctxForDownstream == null) {
      ctxForDownstream = ctx.getPipeline().getContext(HttpServerTracingHandler.class);
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
      ctx.sendUpstream(e);
      return;
    }

    HttpRequest request = (HttpRequest) msg;

    int httpCode = BlockingActionHelper.getHttpCode(statusCode);
    HttpResponseStatus httpResponseStatus = HttpResponseStatus.valueOf(httpCode);
    DefaultHttpResponse response =
        new DefaultHttpResponse(request.getProtocolVersion(), httpResponseStatus);

    HttpHeaders headers = response.headers();
    headers.set("Connection", "close");

    for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
      headers.set(h.getKey(), h.getValue());
    }

    response.setChunked(false);
    if (bct != BlockingContentType.NONE) {
      String acceptHeader = request.headers().get("accept");
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(bct, acceptHeader);
      headers.set("Content-type", BlockingActionHelper.getContentType(type));
      byte[] template = BlockingActionHelper.getTemplate(type);
      setContentLength(response, template.length);
      ChannelBuffer buf = ChannelBuffers.wrappedBuffer(template);
      response.setContent(buf);
    }

    this.hasBlockedAlready = true;
    segment.effectivelyBlocked();

    Channels.write(ctxForDownstream.getChannel(), response)
        .addListener(
            fut -> {
              if (!fut.isSuccess()) {
                log.warn("Write of blocking response failed", fut.getCause());
              }
              // close the connection because it can be in an invalid state at this point
              // For instance, in a POST request we will still be receiving data from the
              // client
              fut.getChannel().close();
            });
  }
}
