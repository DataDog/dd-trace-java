package datadog.trace.instrumentation.netty38.server;

import static datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator.NettyBlockResponseFunction.log;
import static org.jboss.netty.handler.codec.http.HttpHeaders.setContentLength;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import java.util.Map;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

public class MaybeBlockResponseHandler extends SimpleChannelDownstreamHandler {
  private final ContextStore<Channel, ChannelTraceContext> contextStore;

  public MaybeBlockResponseHandler(final ContextStore<Channel, ChannelTraceContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void writeRequested(ChannelHandlerContext ctx, MessageEvent msg) throws Exception {
    final ChannelTraceContext channelTraceContext =
        contextStore.putIfAbsent(ctx.getChannel(), ChannelTraceContext.Factory.INSTANCE);

    AgentSpan span = channelTraceContext.getServerSpan();
    RequestContext requestContext;
    if (span == null
        || (requestContext = span.getRequestContext()) == null
        || requestContext.getData(RequestContextSlot.APPSEC) == null) {
      ctx.sendDownstream(msg);
      return;
    }

    if (channelTraceContext.isAnalyzedResponse()) {
      if (channelTraceContext.isBlockedResponse()) {
        // block further writes
      } else {
        ctx.sendDownstream(msg);
      }
      return;
    }

    if (!(msg.getMessage() instanceof HttpResponse)) {
      ctx.sendDownstream(msg);
      return;
    }

    HttpResponse origResponse = (HttpResponse) msg.getMessage();
    if (origResponse.getStatus() == HttpResponseStatus.CONTINUE) {
      ctx.sendDownstream(msg);
      return;
    }

    Flow<Void> flow =
        DECORATE.callIGCallbackResponseAndHeaders(
            span, origResponse, origResponse.getStatus().getCode(), ResponseExtractAdapter.GETTER);
    channelTraceContext.setAnalyzedResponse(true);
    Flow.Action action = flow.getAction();
    if (!(action instanceof Flow.Action.RequestBlockingAction)) {
      ctx.sendDownstream(msg);
      return;
    }

    channelTraceContext.setBlockedResponse(true);
    Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
    int httpCode = BlockingActionHelper.getHttpCode(rba.getStatusCode());
    HttpResponseStatus httpResponseStatus = HttpResponseStatus.valueOf(httpCode);
    DefaultHttpResponse response =
        new DefaultHttpResponse(origResponse.getProtocolVersion(), httpResponseStatus);

    HttpHeaders headers = response.headers();
    headers.set("Connection", "close");

    for (Map.Entry<String, String> h : rba.getExtraHeaders().entrySet()) {
      headers.set(h.getKey(), h.getValue());
    }

    response.setChunked(false);
    BlockingContentType bct = rba.getBlockingContentType();
    if (bct != BlockingContentType.NONE) {
      String acceptHeader = channelTraceContext.getRequestHeaders().get("accept");
      BlockingActionHelper.TemplateType type =
          BlockingActionHelper.determineTemplateType(bct, acceptHeader);
      headers.set("Content-type", BlockingActionHelper.getContentType(type));
      byte[] template = BlockingActionHelper.getTemplate(type, rba.getSecurityResponseId());
      setContentLength(response, template.length);
      ChannelBuffer buf = ChannelBuffers.wrappedBuffer(template);
      response.setContent(buf);
    }

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

    requestContext.getTraceSegment().effectivelyBlocked();

    Channels.write(ctx, future, response);
  }
}
