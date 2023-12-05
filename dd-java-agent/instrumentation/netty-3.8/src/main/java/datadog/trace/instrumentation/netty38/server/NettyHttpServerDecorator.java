package datadog.trace.instrumentation.netty38.server;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.HOST;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelUpstreamHandler;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.UpstreamMessageEvent;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
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
    return new String[] {"netty", "netty-3.9"};
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
    return httpRequest.getMethod().getName();
  }

  @Override
  protected URIDataAdapter url(final HttpRequest request) {
    return URIDataAdapterBase.fromURI(
        request.getUri(),
        uri -> {
          if ((uri.getHost() == null || uri.getHost().equals(""))
              && request.headers().contains(HOST)) {
            return URIDataAdapterBase.fromURI(
                "http://" + request.headers().get(HOST) + request.getUri(),
                URIDefaultDataAdapter::new);
          }
          return new URIDefaultDataAdapter(uri);
        });
  }

  @Override
  protected String peerHostIP(final Channel channel) {
    final SocketAddress socketAddress = channel.getRemoteAddress();
    if (socketAddress instanceof InetSocketAddress) {
      return ((InetSocketAddress) socketAddress).getAddress().getHostAddress();
    }
    return null;
  }

  @Override
  protected int peerPort(final Channel channel) {
    final SocketAddress socketAddress = channel.getRemoteAddress();
    if (socketAddress instanceof InetSocketAddress) {
      return ((InetSocketAddress) socketAddress).getPort();
    }
    return 0;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getStatus().getCode();
  }

  @Override
  protected boolean isAppSecOnResponseSeparate() {
    return true;
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(
      HttpRequest httpRequest, Channel channel) {
    return new NettyBlockResponseFunction(channel.getPipeline(), httpRequest);
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
        Map<String, String> extraHeaders) {
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
        pipeline.addAfter(
            handlerBefore.getClass().getName(),
            "blocking_handler",
            new BlockingResponseHandler(segment, statusCode, templateType, extraHeaders));
        pipeline.addBefore(
            "blocking_handler", "before_blocking_handler", new SimpleChannelUpstreamHandler());
      } catch (RuntimeException rte) {
        log.warn("Failed adding blocking handler");
      }

      // prevent BlockingException from being handled, in order to avoid the existing
      // handlers trying to write an error response
      pipeline.addFirst(
          "ignore_blocking_exception_handler", IgnoreBlockingExceptionHandler.INSTANCE);

      ChannelHandlerContext context = pipeline.getContext("before_blocking_handler");
      context.sendUpstream(
          new UpstreamMessageEvent(pipeline.getChannel(), httpRequestMessage, null));

      return true;
    }
  }

  public static class IgnoreBlockingExceptionHandler extends SimpleChannelUpstreamHandler {
    public static ChannelUpstreamHandler INSTANCE = new IgnoreBlockingExceptionHandler();

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
      if ((e.getCause() instanceof BlockingException)) {
        NettyBlockResponseFunction.log.info("Suppressing handling of BlockingException");
        e.getFuture().setSuccess();
      } else {
        super.exceptionCaught(ctx, e);
      }
    }
  }
}
