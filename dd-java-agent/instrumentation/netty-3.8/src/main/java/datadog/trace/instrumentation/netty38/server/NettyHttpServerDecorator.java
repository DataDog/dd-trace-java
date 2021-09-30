package datadog.trace.instrumentation.netty38.server;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.HOST;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class NettyHttpServerDecorator
    extends HttpServerDecorator<HttpRequest, Channel, HttpResponse, HttpHeaders> {
  public static final CharSequence NETTY = UTF8BytesString.create("netty");
  public static final CharSequence NETTY_CONNECT = UTF8BytesString.create("netty.connect");
  public static final CharSequence NETTY_REQUEST = UTF8BytesString.create("netty.request");
  public static final NettyHttpServerDecorator DECORATE = new NettyHttpServerDecorator();

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
  public CharSequence spanName() {
    return NETTY_REQUEST;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.getMethod().getName();
  }

  @Override
  protected URIDataAdapter url(final HttpRequest request) {
    final URI uri = URI.create(request.getUri());
    if ((uri.getHost() == null || uri.getHost().equals("")) && request.headers().contains(HOST)) {
      return new URIDefaultDataAdapter(
          URI.create("http://" + request.headers().get(HOST) + request.getUri()));
    } else {
      return new URIDefaultDataAdapter(uri);
    }
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
}
