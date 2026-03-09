package datadog.communication.http.client.netty;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpClientBuilder;
import datadog.communication.http.client.HttpTransport;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.concurrent.ThreadFactory;
import javax.annotation.Nullable;

/** Builder used to configure and create {@link NettyHttpClient}. */
public final class NettyHttpClientBuilder
    implements HttpClientBuilder<NettyHttpClientBuilder> {
  private HttpTransport transport = HttpTransport.TCP;
  private String unixDomainSocketPath;
  private String namedPipe;
  private long connectTimeoutMillis = 10_000;
  private long readTimeoutMillis = 10_000;
  private long writeTimeoutMillis = 10_000;
  private long requestTimeoutMillis = 10_000;
  private int maxResponseSizeBytes = 8 * 1024 * 1024;
  private String proxyHost;
  private Integer proxyPort;
  private String proxyUsername;
  private String proxyPassword;
  private HttpRetryPolicy.Factory retryPolicyFactory = HttpRetryPolicy.Factory.NEVER_RETRY;
  private boolean daemonThreads = true;
  private String threadNamePrefix = "dd-netty-http";
  private EventLoopGroup eventLoopGroup;
  private boolean closeEventLoopGroupOnClose = true;

  @Override
  public NettyHttpClientBuilder transport(HttpTransport transport) {
    this.transport = transport;
    return this;
  }

  @Override
  public NettyHttpClientBuilder unixDomainSocketPath(@Nullable String unixDomainSocketPath) {
    this.unixDomainSocketPath = unixDomainSocketPath;
    return this;
  }

  @Override
  public NettyHttpClientBuilder namedPipe(@Nullable String namedPipe) {
    this.namedPipe = namedPipe;
    return this;
  }

  @Override
  public NettyHttpClientBuilder connectTimeoutMillis(long connectTimeoutMillis) {
    this.connectTimeoutMillis = connectTimeoutMillis;
    return this;
  }

  public NettyHttpClientBuilder readTimeoutMillis(long readTimeoutMillis) {
    this.readTimeoutMillis = readTimeoutMillis;
    return this;
  }

  public NettyHttpClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
    this.writeTimeoutMillis = writeTimeoutMillis;
    return this;
  }

  @Override
  public NettyHttpClientBuilder requestTimeoutMillis(long requestTimeoutMillis) {
    this.requestTimeoutMillis = requestTimeoutMillis;
    return this;
  }

  public NettyHttpClientBuilder maxResponseSizeBytes(int maxResponseSizeBytes) {
    this.maxResponseSizeBytes = maxResponseSizeBytes;
    return this;
  }

  @Override
  public NettyHttpClientBuilder proxy(String proxyHost, int proxyPort) {
    return proxy(proxyHost, proxyPort, null, null);
  }

  @Override
  public NettyHttpClientBuilder proxy(
      String proxyHost,
      int proxyPort,
      @Nullable String proxyUsername,
      @Nullable String proxyPassword) {
    this.proxyHost = proxyHost;
    this.proxyPort = proxyPort;
    this.proxyUsername = proxyUsername;
    this.proxyPassword = proxyPassword;
    return this;
  }

  @Override
  public NettyHttpClientBuilder retryPolicyFactory(HttpRetryPolicy.Factory retryPolicyFactory) {
    this.retryPolicyFactory = retryPolicyFactory;
    return this;
  }

  public NettyHttpClientBuilder daemonThreads(boolean daemonThreads) {
    this.daemonThreads = daemonThreads;
    return this;
  }

  public NettyHttpClientBuilder threadNamePrefix(String threadNamePrefix) {
    this.threadNamePrefix = threadNamePrefix;
    return this;
  }

  public NettyHttpClientBuilder eventLoopGroup(
      EventLoopGroup eventLoopGroup, boolean closeEventLoopGroupOnClose) {
    this.eventLoopGroup = eventLoopGroup;
    this.closeEventLoopGroupOnClose = closeEventLoopGroupOnClose;
    return this;
  }

  @Override
  public HttpClient build() {
    validate();

    ThreadFactory threadFactory = new DefaultThreadFactory(threadNamePrefix, daemonThreads);
    return new NettyHttpClient(
        transport,
        unixDomainSocketPath,
        namedPipe,
        connectTimeoutMillis,
        readTimeoutMillis,
        writeTimeoutMillis,
        requestTimeoutMillis,
        maxResponseSizeBytes,
        proxyHost,
        proxyPort,
        proxyUsername,
        proxyPassword,
        retryPolicyFactory,
        eventLoopGroup,
        closeEventLoopGroupOnClose,
        threadFactory);
  }

  private void validate() {
    if (connectTimeoutMillis <= 0
        || readTimeoutMillis <= 0
        || writeTimeoutMillis <= 0
        || requestTimeoutMillis <= 0) {
      throw new IllegalArgumentException("timeouts must be strictly positive");
    }
    if (maxResponseSizeBytes <= 0) {
      throw new IllegalArgumentException("maxResponseSizeBytes must be strictly positive");
    }
    if (proxyHost != null && proxyPort == null) {
      throw new IllegalArgumentException("proxy port must be provided when proxy host is set");
    }
    if (proxyHost != null && transport != HttpTransport.TCP) {
      throw new IllegalArgumentException("proxy is currently supported only for TCP transport");
    }
    if (transport == HttpTransport.UNIX_DOMAIN_SOCKET
        && (unixDomainSocketPath == null || unixDomainSocketPath.isEmpty())) {
      throw new IllegalArgumentException(
          "unix domain socket path must be set for UNIX_DOMAIN_SOCKET transport");
    }
    if (transport == HttpTransport.NAMED_PIPE && (namedPipe == null || namedPipe.isEmpty())) {
      throw new IllegalArgumentException("named pipe must be set for NAMED_PIPE transport");
    }
  }
}
