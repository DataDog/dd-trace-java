package datadog.trace.instrumentation.netty40.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.netty40.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.client.NettyHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.netty40.client.NettyHttpClientDecorator.DECORATE_SECURE;
import static datadog.trace.instrumentation.netty40.client.NettyHttpClientDecorator.NETTY_CLIENT_REQUEST;
import static datadog.trace.instrumentation.netty40.client.NettyResponseInjectAdapter.SETTER;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpRequest;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

@ChannelHandler.Sharable
public class HttpClientRequestTracingHandler extends ChannelOutboundHandlerAdapter {
  public static final HttpClientRequestTracingHandler INSTANCE =
      new HttpClientRequestTracingHandler();
  private static final Class<ChannelHandler> SSL_HANDLER;

  static {
    Class<?> sslHandler;
    try {
      // This class is in "netty-handler", so ignore if not present.
      ClassLoader cl = HttpClientRequestTracingHandler.class.getClassLoader();
      sslHandler = Class.forName("io.netty.handler.ssl.SslHandler", false, cl);
    } catch (ClassNotFoundException e) {
      sslHandler = null;
    }
    SSL_HANDLER = (Class<ChannelHandler>) sslHandler;
  }

  private static final boolean AWS_LEGACY_TRACING =
      Config.get().isLegacyTracingEnabled(false, "aws-sdk");

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    if (!(msg instanceof HttpRequest)) {
      ctx.write(msg, prm);
      return;
    }

    AgentScope parentScope = null;
    final AgentScope.Continuation continuation =
        ctx.channel().attr(CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY).getAndRemove();
    if (continuation != null) {
      parentScope = continuation.activate();
    }

    final HttpRequest request = (HttpRequest) msg;
    boolean awsClientCall = request.headers().contains("amz-sdk-invocation-id");
    if (!AWS_LEGACY_TRACING && awsClientCall) {
      // avoid creating an extra HTTP client span beneath the AWS client call
      try {
        ctx.write(msg, prm);
        return;
      } finally {
        if (null != parentScope) {
          parentScope.close();
        }
      }
    }

    ctx.channel().attr(CLIENT_PARENT_ATTRIBUTE_KEY).set(activeSpan());
    boolean isSecure = SSL_HANDLER != null && ctx.pipeline().get(SSL_HANDLER) != null;
    NettyHttpClientDecorator decorate = isSecure ? DECORATE_SECURE : DECORATE;

    final AgentSpan span = startSpan(NETTY_CLIENT_REQUEST);
    try (final AgentScope scope = activateSpan(span)) {
      decorate.afterStart(span);
      decorate.onRequest(span, request);

      SocketAddress socketAddress = ctx.channel().remoteAddress();
      if (socketAddress instanceof InetSocketAddress) {
        decorate.onPeerConnection(span, (InetSocketAddress) socketAddress);
      }

      // AWS calls are often signed, so we can't add headers without breaking the signature.
      if (!awsClientCall) {
        propagate().inject(span, request.headers(), SETTER);
        propagate()
            .injectPathwayContext(
                span, request.headers(), SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
      }

      ctx.channel().attr(SPAN_ATTRIBUTE_KEY).set(span);

      try {
        ctx.write(msg, prm);
      } catch (final Throwable throwable) {
        decorate.onError(span, throwable);
        decorate.beforeFinish(span);
        span.finish();
        throw throwable;
      }
    } finally {
      if (null != parentScope) {
        parentScope.close();
      }
    }
  }
}
