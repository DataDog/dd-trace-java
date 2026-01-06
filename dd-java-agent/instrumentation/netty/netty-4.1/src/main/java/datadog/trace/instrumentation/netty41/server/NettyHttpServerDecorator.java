package datadog.trace.instrumentation.netty41.server;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyHttpServerDecorator
    extends HttpServerDecorator<HttpRequest, Channel, HttpResponse, HttpHeaders> {
  public static final CharSequence NETTY = UTF8BytesString.create("netty");
  public static final CharSequence NETTY_CONNECT = UTF8BytesString.create("netty.connect");

  public static final NettyHttpServerDecorator DECORATE = new NettyHttpServerDecorator();
  private static final CharSequence NETTY_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"netty", "netty-4.0"};
  }

  @Override
  protected CharSequence component() {
    return NETTY;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpHeaders> getter() {
    return ContextVisitors.stringValuesEntrySet();
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return ResponseExtractAdapter.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return NETTY_REQUEST;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.method().name();
  }

  @Override
  protected URIDataAdapter url(final HttpRequest request) {
    return URIDataAdapterBase.fromURI(
        request.getUri(),
        uri -> {
          if ((uri.getHost() == null || uri.getHost().equals(""))
              && request.headers().contains(HttpHeaders.Names.HOST)) {
            return URIDataAdapterBase.fromURI(
                "http://" + request.headers().get(HttpHeaders.Names.HOST) + request.getUri(),
                URIDefaultDataAdapter::new);
          }
          return new URIDefaultDataAdapter(uri);
        });
  }

  @Override
  protected String peerHostIP(final Channel channel) {
    final SocketAddress socketAddress = channel.remoteAddress();
    if (socketAddress instanceof InetSocketAddress) {
      return ((InetSocketAddress) socketAddress).getAddress().getHostAddress();
    }
    return null;
  }

  @Override
  protected int peerPort(final Channel channel) {
    final SocketAddress socketAddress = channel.remoteAddress();
    if (socketAddress instanceof InetSocketAddress) {
      return ((InetSocketAddress) socketAddress).getPort();
    }
    return 0;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.status().code();
  }

  @Override
  protected boolean isAppSecOnResponseSeparate() {
    return true;
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(
      HttpRequest httpRequest, Channel channel) {
    return new NettyBlockResponseFunction(channel.pipeline(), httpRequest);
  }

  public static class NettyBlockResponseFunction implements BlockResponseFunction {
    private final ChannelPipeline pipeline;
    public static final Logger log = LoggerFactory.getLogger(NettyBlockResponseFunction.class);
    private final HttpRequest httpRequestMessage;

    public NettyBlockResponseFunction(ChannelPipeline pipeline, HttpRequest httpRequestMessage) {
      this.pipeline = pipeline;
      this.httpRequestMessage = httpRequestMessage;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      ChannelHandler handlerBefore = pipeline.get(HttpServerTracingHandler.class);
      if (handlerBefore == null) {
        handlerBefore = pipeline.get(HttpServerRequestTracingHandler.class);
        if (handlerBefore == null) {
          log.warn(
              "Can't block without an HttpServerTracingHandler or HttpServerRequestTracingHandler in the pipeline");
          return false;
        }
      }

      try {
        pipeline
            .addAfter(
                pipeline.context(handlerBefore).name(),
                "blocking_handler",
                new BlockingResponseHandler(
                    segment, statusCode, templateType, extraHeaders, securityResponseId))
            .addBefore(
                "blocking_handler", "before_blocking_handler", new ChannelInboundHandlerAdapter());
      } catch (RuntimeException rte) {
        log.warn("Failed adding blocking handler", rte);
        return false;
      }

      ChannelHandlerContext context = pipeline.context("before_blocking_handler");
      context.fireChannelRead(httpRequestMessage);

      return true;
    }
  }
}
