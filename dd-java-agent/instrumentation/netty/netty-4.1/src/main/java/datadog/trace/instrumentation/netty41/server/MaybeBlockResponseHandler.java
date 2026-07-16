package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;
import static io.netty.handler.codec.http.HttpHeaders.setContentLength;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ChannelHandler.Sharable
public class MaybeBlockResponseHandler extends ChannelOutboundHandlerAdapter {
  public static final ChannelOutboundHandler INSTANCE = new MaybeBlockResponseHandler();
  public static final Logger log = LoggerFactory.getLogger(MaybeBlockResponseHandler.class);

  private MaybeBlockResponseHandler() {}

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise prm) throws Exception {
    Channel channel = ctx.channel();

    if (ServerRequestContext.isResponseBlocked(channel)) {
      // block further writes while the blocking response close is still asynchronous
      log.debug("Write suppressed, msg {} dropped", msg);
      ReferenceCountUtil.release(msg);
      return;
    }

    ServerRequestContext serverContext = ServerRequestContext.nextResponse(channel);
    Context storedContext = serverContext == null ? null : serverContext.tracingContext();
    AgentSpan span = AgentSpan.fromContext(storedContext);
    RequestContext requestContext;
    if (span == null || (requestContext = span.getRequestContext()) == null) {
      super.write(ctx, msg, prm);
      return;
    }

    if (serverContext.isResponseAnalyzed()) {
      super.write(ctx, msg, prm);
      return;
    }

    if (!(msg instanceof HttpResponse)) {
      super.write(ctx, msg, prm);
      return;
    }
    HttpResponse origResponse = (HttpResponse) msg;
    if (origResponse.status().code() == HttpResponseStatus.CONTINUE.code()) {
      super.write(ctx, msg, prm);
      return;
    }

    Flow<Void> flow =
        DECORATE.callIGCallbackResponseAndHeaders(
            span, origResponse, origResponse.getStatus().code(), ResponseExtractAdapter.GETTER);
    serverContext.markResponseAnalyzed();
    Flow.Action action = flow.getAction();
    if (!(action instanceof Flow.Action.RequestBlockingAction)) {
      super.write(ctx, msg, prm);
      return;
    }

    ServerRequestContext.markResponseBlocked(channel);
    Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
    int httpCode = BlockingActionHelper.getHttpCode(rba.getStatusCode());
    HttpResponseStatus httpResponseStatus = HttpResponseStatus.valueOf(httpCode);
    FullHttpResponse response =
        new DefaultFullHttpResponse(origResponse.getProtocolVersion(), httpResponseStatus);
    ReferenceCountUtil.release(msg);

    HttpHeaders headers = response.headers();
    headers.set("Connection", "close");

    for (Map.Entry<String, String> h : rba.getExtraHeaders().entrySet()) {
      headers.set(h.getKey(), h.getValue());
    }

    BlockingContentType bct = rba.getBlockingContentType();
    if (bct != BlockingContentType.NONE) {
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(bct, serverContext.acceptHeader());
      headers.set("Content-type", BlockingActionHelper.getContentType(type));
      byte[] template = BlockingActionHelper.getTemplate(type, rba.getSecurityResponseId());
      setContentLength(response, template.length);
      response.content().writeBytes(template);
    }

    requestContext.getTraceSegment().effectivelyBlocked();
    log.debug("About to write and flush blocking response {}", response);
    ctx.writeAndFlush(response, prm)
        .addListener(
            fut -> {
              if (!fut.isSuccess()) {
                log.warn("Write of blocking response failed", fut.cause());
              } else {
                log.debug("Write of blocking response succeeded");
              }
              channel.close();
            });
  }
}
