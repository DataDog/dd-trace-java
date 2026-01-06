package datadog.trace.instrumentation.netty38.server;

import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
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
  private final String securityResponseId;

  private static final Logger log = LoggerFactory.getLogger(BlockingResponseHandler.class);
  private static volatile boolean HAS_WARNED;

  private boolean hasBlockedAlready;

  public BlockingResponseHandler(
      TraceSegment segment,
      int statusCode,
      BlockingContentType bct,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    this.segment = segment;
    this.statusCode = statusCode;
    this.bct = bct;
    this.extraHeaders = extraHeaders;
    this.securityResponseId = securityResponseId;
  }

  public BlockingResponseHandler(TraceSegment segment, Flow.Action.RequestBlockingAction rba) {
    this(
        segment,
        rba.getStatusCode(),
        rba.getBlockingContentType(),
        rba.getExtraHeaders(),
        rba.getSecurityResponseId());
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

    // the usual disposition of the pipeline is:
    // ...
    // (1) BlockingResponseHandler (upstream, this handler)
    // (2) HttpServerResponseTracingHandler / HttpServerTracingHandler (downstream / duplex)
    // (3) MaybeBlockResponseHandler (downstream)
    // ...
    // when writing our blocking response, we want (2) to analyze it to report
    // the status code, etc., but we need not run MaybeBlockResponseHandler
    // because response blocking on a blocking response is not supported.
    // We add a handler after (3) blocking all further writes, in case something
    // tries to write after we've written the blocking response
    ChannelHandlerContext ctxForDownstream = null;
    try {
      ctx.getPipeline()
          .addAfter(
              MaybeBlockResponseHandler.class.getName(),
              "block_all_writes",
              BlockAllWritesHandler.INSTANCE);
      // write will start AFTER the referenced handler. It will start in (2)
      ctxForDownstream = ctx.getPipeline().getContext(MaybeBlockResponseHandler.class.getName());
    } catch (NoSuchElementException nse) {
      if (HAS_WARNED) {
        log.debug(
            "Unable to block because MaybeBlockResponseHandler was not found on the pipeline");
      } else {
        log.warn("Unable to block because MaybeBlockResponseHandler was not found on the pipeline");
        HAS_WARNED = true;
      }
    } catch (IllegalArgumentException iae) {
      // already added
    }

    if (ctxForDownstream == null) {
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
      byte[] template = BlockingActionHelper.getTemplate(type, this.securityResponseId);
      setContentLength(response, template.length);
      ChannelBuffer buf = ChannelBuffers.wrappedBuffer(template);
      response.setContent(buf);
    }

    this.hasBlockedAlready = true;
    segment.effectivelyBlocked();

    ChannelFuture future = Channels.future(ctx.getChannel());
    future.addListener(
        fut -> {
          if (!fut.isSuccess()) {
            log.warn("Write of blocking response failed", fut.getCause());
          }
          // close the connection because it can be in an invalid state at this point
          // For instance, in a POST request we will still be receiving data from the
          // client
          fut.getChannel().close();
        });
    Channels.write(ctxForDownstream, future, response);
  }
}
