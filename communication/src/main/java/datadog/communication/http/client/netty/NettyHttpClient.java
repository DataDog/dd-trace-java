package datadog.communication.http.client.netty;

import datadog.common.socket.NamedPipeSocketFactory;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpClientRequest;
import datadog.communication.http.client.HttpClientResponse;
import datadog.communication.http.client.HttpTransport;
import datadog.trace.util.AgentProxySelector;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.oio.OioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import javax.net.SocketFactory;

final class NettyHttpClient implements HttpClient {
  private final HttpTransport transport;
  private final long connectTimeoutMillis;
  private final long readTimeoutMillis;
  private final long writeTimeoutMillis;
  private final long requestTimeoutMillis;
  private final int maxResponseSizeBytes;
  private final String proxyHost;
  private final Integer proxyPort;
  private final String proxyUsername;
  private final String proxyPassword;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final EventLoopGroup eventLoopGroup;
  private final boolean closeEventLoopGroupOnClose;
  private final SslContext sslContext;
  private final SocketFactory socketFactory;
  private final Set<Channel> inFlightChannels =
      Collections.newSetFromMap(new ConcurrentHashMap<Channel, Boolean>());
  private volatile boolean closed;

  NettyHttpClient(
      HttpTransport transport,
      @Nullable String unixDomainSocketPath,
      @Nullable String namedPipe,
      long connectTimeoutMillis,
      long readTimeoutMillis,
      long writeTimeoutMillis,
      long requestTimeoutMillis,
      int maxResponseSizeBytes,
      @Nullable String proxyHost,
      @Nullable Integer proxyPort,
      @Nullable String proxyUsername,
      @Nullable String proxyPassword,
      HttpRetryPolicy.Factory retryPolicyFactory,
      @Nullable EventLoopGroup externallyProvidedEventLoopGroup,
      boolean closeEventLoopGroupOnClose,
      ThreadFactory threadFactory) {
    this.transport = transport;
    this.connectTimeoutMillis = connectTimeoutMillis;
    this.readTimeoutMillis = readTimeoutMillis;
    this.writeTimeoutMillis = writeTimeoutMillis;
    this.requestTimeoutMillis = requestTimeoutMillis;
    this.maxResponseSizeBytes = maxResponseSizeBytes;
    this.proxyHost = proxyHost;
    this.proxyPort = proxyPort;
    this.proxyUsername = proxyUsername;
    this.proxyPassword = proxyPassword;
    this.retryPolicyFactory = retryPolicyFactory;

    if (externallyProvidedEventLoopGroup != null) {
      this.eventLoopGroup = externallyProvidedEventLoopGroup;
      this.closeEventLoopGroupOnClose = closeEventLoopGroupOnClose;
    } else {
      if (transport == HttpTransport.TCP) {
        this.eventLoopGroup = new NioEventLoopGroup(1, threadFactory);
      } else {
        this.eventLoopGroup = new OioEventLoopGroup(1, threadFactory);
      }
      this.closeEventLoopGroupOnClose = true;
    }

    this.sslContext = buildSslContext();
    this.socketFactory = socketFactoryForTransport(transport, unixDomainSocketPath, namedPipe);
  }

  @Override
  public HttpClientResponse execute(HttpClientRequest request) throws IOException {
    if (closed) {
      throw new IOException("http client is closed");
    }
    try (HttpRetryPolicy retryPolicy = retryPolicyFactory.create()) {
      while (true) {
        try {
          HttpClientResponse response = executeOnce(request);
          if (!retryPolicy.shouldRetry(new ResponseAdapter(response))) {
            return response;
          }
        } catch (Exception e) {
          if (!retryPolicy.shouldRetry(
              e instanceof IOException ? (IOException) e : new IOException(e))) {
            if (e instanceof IOException) {
              throw (IOException) e;
            }
            throw new IOException(e);
          }
        }
        retryPolicy.backoff();
      }
    }
  }

  private HttpClientResponse executeOnce(HttpClientRequest request)
      throws IOException, InterruptedException {
    if (transport != HttpTransport.TCP) {
      return executeWithSocketTransport(request);
    }

    URI uri = request.uri();
    int port = port(uri);
    String host = uri.getHost() != null ? uri.getHost() : "localhost";

    Bootstrap bootstrap =
        new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMillis);

    CompletableFuture<HttpClientResponse> responseFuture = new CompletableFuture<>();
    bootstrap.handler(
        new ClientInitializer(uri, readTimeoutMillis, writeTimeoutMillis, responseFuture));

    ChannelFuture connectFuture = bootstrap.connect(host, port);
    try {
      if (!connectFuture.await(connectTimeoutMillis, TimeUnit.MILLISECONDS)) {
        throw new IOException("connection timeout");
      }
      if (!connectFuture.isSuccess()) {
        throw new IOException("connection failed", connectFuture.cause());
      }

      Channel channel = connectFuture.channel();
      inFlightChannels.add(channel);
      channel
          .closeFuture()
          .addListener(
              ignored -> responseFuture.completeExceptionally(new IOException("channel closed")));
      if (closed) {
        throw new IOException("http client is closed");
      }
      channel.writeAndFlush(toNettyRequest(request, uri, host, port));

      return responseFuture.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw cause instanceof IOException ? (IOException) cause : new IOException(cause);
    } catch (TimeoutException e) {
      throw new IOException("request timeout", e);
    } finally {
      if (connectFuture.channel() != null) {
        inFlightChannels.remove(connectFuture.channel());
        connectFuture.channel().close().awaitUninterruptibly();
      }
    }
  }

  private HttpClientResponse executeWithSocketTransport(HttpClientRequest request)
      throws IOException, InterruptedException {
    URI uri = request.uri();
    String host = uri.getHost() != null ? uri.getHost() : "localhost";
    int port = port(uri);

    try (Socket socket = createSocket(socketFactory)) {
      socket.connect(new InetSocketAddress(host, port), (int) connectTimeoutMillis);
      socket.setSoTimeout((int) readTimeoutMillis);

      try (BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
          BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
        byte[] body = request.body();
        String requestLine = request.method() + " " + rawPathAndQuery(uri) + " HTTP/1.1\r\n";
        output.write(requestLine.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(
            ("Host: " + hostHeaderValue(uri, host, port) + "\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write("Connection: close\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
          for (String value : header.getValue()) {
            output.write(
                (header.getKey() + ": " + value + "\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          }
        }
        output.write(
            ("Content-Length: " + body.length + "\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();

        return readSocketHttpResponse(input);
      }
    }
  }

  private HttpClientResponse readSocketHttpResponse(BufferedInputStream input) throws IOException {
    String statusLine = readAsciiLine(input);
    if (statusLine == null || !statusLine.startsWith("HTTP/")) {
      throw new IOException("Invalid HTTP response: missing status line");
    }
    String[] statusParts = statusLine.split(" ");
    if (statusParts.length < 2) {
      throw new IOException("Invalid HTTP response status line: " + statusLine);
    }
    int statusCode = Integer.parseInt(statusParts[1]);

    Map<String, List<String>> headers = new LinkedHashMap<>();
    int contentLength = -1;
    while (true) {
      String line = readAsciiLine(input);
      if (line == null || line.isEmpty()) {
        break;
      }
      int separator = line.indexOf(':');
      if (separator > 0) {
        String name = line.substring(0, separator).trim();
        String value = line.substring(separator + 1).trim();
        headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        if ("Content-Length".equalsIgnoreCase(name)) {
          contentLength = Integer.parseInt(value);
        }
      }
    }

    byte[] body;
    if (contentLength >= 0) {
      body = new byte[contentLength];
      int offset = 0;
      while (offset < contentLength) {
        int read = input.read(body, offset, contentLength - offset);
        if (read < 0) {
          break;
        }
        offset += read;
      }
      if (offset < contentLength) {
        byte[] truncated = new byte[offset];
        System.arraycopy(body, 0, truncated, 0, offset);
        body = truncated;
      }
    } else {
      ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
      byte[] buffer = new byte[8 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        bodyBuffer.write(buffer, 0, read);
      }
      body = bodyBuffer.toByteArray();
    }
    return new HttpClientResponse(statusCode, headers, body);
  }

  private static @Nullable String readAsciiLine(BufferedInputStream input) throws IOException {
    ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
    int previous = -1;
    while (true) {
      int current = input.read();
      if (current == -1) {
        break;
      }
      if (previous == '\r' && current == '\n') {
        byte[] bytes = lineBuffer.toByteArray();
        return new String(
            bytes, 0, Math.max(0, bytes.length - 1), java.nio.charset.StandardCharsets.US_ASCII);
      }
      lineBuffer.write(current);
      previous = current;
    }
    return lineBuffer.size() == 0
        ? null
        : new String(lineBuffer.toByteArray(), java.nio.charset.StandardCharsets.US_ASCII);
  }

  private DefaultFullHttpRequest toNettyRequest(
      HttpClientRequest request, URI uri, String host, int port) {
    HttpMethod method = HttpMethod.valueOf(request.method());
    byte[] body = request.body();

    DefaultFullHttpRequest nettyRequest =
        new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, method, rawPathAndQuery(uri), Unpooled.wrappedBuffer(body));

    for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
      for (String value : entry.getValue()) {
        nettyRequest.headers().add(entry.getKey(), value);
      }
    }

    if (!nettyRequest.headers().contains(HttpHeaderNames.HOST)) {
      nettyRequest.headers().set(HttpHeaderNames.HOST, hostHeaderValue(uri, host, port));
    }
    if (!nettyRequest.headers().contains(HttpHeaderNames.CONNECTION)) {
      nettyRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    }
    if (!nettyRequest.headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
      nettyRequest.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
    }

    return nettyRequest;
  }

  private static String rawPathAndQuery(URI uri) {
    String rawPath = uri.getRawPath();
    if (rawPath == null || rawPath.isEmpty()) {
      rawPath = "/";
    }
    String rawQuery = uri.getRawQuery();
    return rawQuery == null || rawQuery.isEmpty() ? rawPath : rawPath + "?" + rawQuery;
  }

  private String hostHeaderValue(URI uri, String host, int port) {
    if (uri.getPort() == -1 || uri.getPort() == defaultPort(uri.getScheme())) {
      return host;
    }
    return host + ':' + port;
  }

  private int port(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return defaultPort(uri.getScheme());
  }

  private static int defaultPort(@Nullable String scheme) {
    if ("https".equalsIgnoreCase(scheme)) {
      return 443;
    }
    return 80;
  }

  private SslContext buildSslContext() {
    try {
      return SslContextBuilder.forClient().build();
    } catch (Exception e) {
      throw new IllegalStateException("Unable to create Netty SSL context", e);
    }
  }

  private static SocketFactory socketFactoryForTransport(
      HttpTransport transport, @Nullable String unixDomainSocketPath, @Nullable String namedPipe) {
    if (transport == HttpTransport.UNIX_DOMAIN_SOCKET) {
      return new UnixDomainSocketFactory(new java.io.File(unixDomainSocketPath));
    }
    if (transport == HttpTransport.NAMED_PIPE) {
      return new NamedPipeSocketFactory(namedPipe);
    }
    return SocketFactory.getDefault();
  }

  private static Socket createSocket(SocketFactory factory) {
    try {
      return factory.createSocket();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to create socket", e);
    }
  }

  @Override
  public void close() {
    closed = true;
    for (Channel channel : inFlightChannels) {
      try {
        channel.close().awaitUninterruptibly();
      } catch (Exception ignored) {
      }
    }
    inFlightChannels.clear();

    if (closeEventLoopGroupOnClose) {
      eventLoopGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly();
    }
  }

  private final class ClientInitializer extends ChannelInitializer<Channel> {
    private final URI uri;
    private final long readTimeoutMillis;
    private final long writeTimeoutMillis;
    private final CompletableFuture<HttpClientResponse> responseFuture;

    private ClientInitializer(
        URI uri,
        long readTimeoutMillis,
        long writeTimeoutMillis,
        CompletableFuture<HttpClientResponse> responseFuture) {
      this.uri = uri;
      this.readTimeoutMillis = readTimeoutMillis;
      this.writeTimeoutMillis = writeTimeoutMillis;
      this.responseFuture = responseFuture;
    }

    @Override
    protected void initChannel(Channel channel) throws Exception {
      InetSocketAddress proxyAddress = resolveProxyAddress(uri);
      if (proxyAddress != null) {
        channel
            .pipeline()
            .addLast(
                proxyUsername != null
                    ? new HttpProxyHandler(proxyAddress, proxyUsername, proxyPassword)
                    : new HttpProxyHandler(proxyAddress));
      }

      if ("https".equalsIgnoreCase(uri.getScheme())) {
        channel
            .pipeline()
            .addLast(sslContext.newHandler(channel.alloc(), uri.getHost(), port(uri)));
      }

      channel
          .pipeline()
          .addLast(new HttpClientCodec())
          .addLast(new HttpObjectAggregator(maxResponseSizeBytes))
          .addLast(new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS))
          .addLast(new WriteTimeoutHandler(writeTimeoutMillis, TimeUnit.MILLISECONDS))
          .addLast(new ResponseHandler(responseFuture));
    }
  }

  private @Nullable InetSocketAddress resolveProxyAddress(URI uri) {
    if (proxyHost != null) {
      return new InetSocketAddress(proxyHost, proxyPort);
    }
    try {
      URI selectorUri = new URI(uri.getScheme(), null, uri.getHost(), port(uri), null, null, null);
      for (Proxy proxy : AgentProxySelector.INSTANCE.select(selectorUri)) {
        if (proxy != null
            && proxy.type() == Proxy.Type.HTTP
            && proxy.address() instanceof InetSocketAddress) {
          return (InetSocketAddress) proxy.address();
        }
      }
    } catch (URISyntaxException ignored) {
      // fall back to no proxy
    }
    return null;
  }

  private static final class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
    private final CompletableFuture<HttpClientResponse> responseFuture;

    private ResponseHandler(CompletableFuture<HttpClientResponse> responseFuture) {
      this.responseFuture = responseFuture;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
      Map<String, List<String>> headers = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : msg.headers()) {
        headers.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue());
      }

      byte[] body = new byte[msg.content().readableBytes()];
      msg.content().readBytes(body);
      responseFuture.complete(new HttpClientResponse(msg.status().code(), headers, body));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      responseFuture.completeExceptionally(cause);
      ctx.close();
    }
  }

  private static final class ResponseAdapter implements HttpRetryPolicy.Response {
    private final HttpClientResponse response;

    private ResponseAdapter(HttpClientResponse response) {
      this.response = response;
    }

    @Override
    public int code() {
      return response.statusCode();
    }

    @Override
    public @Nullable String header(String name) {
      return response.header(name);
    }
  }
}
