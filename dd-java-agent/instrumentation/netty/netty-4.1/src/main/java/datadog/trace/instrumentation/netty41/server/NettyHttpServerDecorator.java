package datadog.trace.instrumentation.netty41.server;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.DDTags;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
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
  private static final String NETTY_NATIVE_IO_EXCEPTION_CLASS_NAME =
      "io.netty.channel.unix.Errors$NativeIoException";
  private static final String NETTY_NATIVE_WRITEV_ADDRESSES_FAILURE_PREFIX =
      "writevAddresses(..) failed";
  private static final String NETTY_NATIVE_WRITEV_SYSCALL_FAILURE_PREFIX =
      "syscall:writev(..) failed";
  private static final String BROKEN_PIPE_MESSAGE_SUFFIX = ": Broken pipe";
  private static final String CONNECTION_RESET_MESSAGE_SUFFIX = ": Connection reset by peer";

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
  protected void doOnError(final AgentSpan span, final Throwable throwable, byte errorPriority) {
    if (isNettyNativeClientAbort(throwable)) {
      span.setTag(DDTags.ERROR_MSG, safeMessage(throwable));
      span.setTag(DDTags.ERROR_TYPE, throwable.getClass().getName());
      return;
    }
    super.doOnError(span, throwable, errorPriority);
  }

  private static boolean isNettyNativeClientAbort(final Throwable throwable) {
    if (throwable == null
        || !NETTY_NATIVE_IO_EXCEPTION_CLASS_NAME.equals(throwable.getClass().getName())) {
      return false;
    }
    final String message = safeMessage(throwable);
    return message != null
        && (message.startsWith(NETTY_NATIVE_WRITEV_ADDRESSES_FAILURE_PREFIX)
            || message.startsWith(NETTY_NATIVE_WRITEV_SYSCALL_FAILURE_PREFIX))
        && (message.endsWith(BROKEN_PIPE_MESSAGE_SUFFIX)
            || message.endsWith(CONNECTION_RESET_MESSAGE_SUFFIX));
  }

  private static String safeMessage(final Throwable throwable) {
    try {
      return throwable.getMessage();
    } catch (Throwable ignored) {
      return null;
    }
  }

  @Override
  protected BlockResponseFunction createBlockResponseFunction(
      HttpRequest httpRequest, Channel channel) {
    return new NettyBlockResponseFunction(
        channel.pipeline(), httpRequest, ServerRequestContext.currentRequest(channel));
  }

  public static class NettyBlockResponseFunction implements BlockResponseFunction {
    public static final Logger log = LoggerFactory.getLogger(NettyBlockResponseFunction.class);

    private final ChannelPipeline pipeline;
    private final HttpVersion protocolVersion;
    private final String acceptHeader;
    private final ServerRequestContext serverContext;

    public NettyBlockResponseFunction(
        ChannelPipeline pipeline,
        HttpRequest httpRequestMessage,
        ServerRequestContext serverContext) {
      this.pipeline = pipeline;
      this.protocolVersion = httpRequestMessage.protocolVersion();
      this.acceptHeader = httpRequestMessage.headers().get("accept");
      this.serverContext = serverContext;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      if (pipeline.channel().eventLoop().inEventLoop()) {
        return commitBlockingResponse(
            segment, statusCode, templateType, extraHeaders, securityResponseId);
      }

      try {
        pipeline
            .channel()
            .eventLoop()
            .execute(
                () ->
                    commitBlockingResponse(
                        segment, statusCode, templateType, extraHeaders, securityResponseId));
        return true;
      } catch (RuntimeException rte) {
        log.warn("Failed scheduling blocking handler", rte);
        return false;
      }
    }

    private boolean commitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      if (serverContext != null
          && !ServerRequestContext.isPending(pipeline.channel(), serverContext)) {
        return false;
      }

      ChannelHandler handlerBefore = pipeline.get(HttpServerTracingHandler.class);
      if (handlerBefore == null) {
        handlerBefore = pipeline.get(HttpServerRequestTracingHandler.class);
        if (handlerBefore == null) {
          log.warn(
              "Can't block without an HttpServerTracingHandler or HttpServerRequestTracingHandler in the pipeline");
          return false;
        }
      }

      BlockingResponseHandler blockingHandler =
          new BlockingResponseHandler(
              segment, statusCode, templateType, extraHeaders, securityResponseId, serverContext);
      ChannelInboundHandlerAdapter beforeBlockingHandler = new ChannelInboundHandlerAdapter();
      try {
        pipeline
            .addAfter(
                pipeline.context(handlerBefore).name(),
                BlockingResponseHandler.BEFORE_BLOCKING_HANDLER_NAME,
                beforeBlockingHandler)
            .addAfter(
                BlockingResponseHandler.BEFORE_BLOCKING_HANDLER_NAME,
                BlockingResponseHandler.HANDLER_NAME,
                blockingHandler);
      } catch (RuntimeException rte) {
        removeHandlerIfPresent(beforeBlockingHandler);
        removeHandlerIfPresent(blockingHandler);
        log.warn("Failed adding blocking handler", rte);
        return false;
      }

      ChannelHandlerContext context = pipeline.context(BlockingResponseHandler.HANDLER_NAME);
      if (blockingHandler.commitBlockingResponse(context, protocolVersion, acceptHeader)) {
        return true;
      }
      removeHandlerIfPresent(beforeBlockingHandler);
      removeHandlerIfPresent(blockingHandler);
      return false;
    }

    private void removeHandlerIfPresent(ChannelHandler handler) {
      if (pipeline.context(handler) != null) {
        pipeline.remove(handler);
      }
    }
  }
}
