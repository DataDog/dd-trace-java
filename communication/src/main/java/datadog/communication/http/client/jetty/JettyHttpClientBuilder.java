package datadog.communication.http.client.jetty;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpClientBuilder;
import datadog.communication.http.client.HttpTransport;
import javax.annotation.Nullable;

/** Builder used to configure and create {@link JettyHttpClient}. */
public final class JettyHttpClientBuilder implements HttpClientBuilder<JettyHttpClientBuilder> {
  private HttpTransport transport = HttpTransport.TCP;
  private String unixDomainSocketPath;
  private long connectTimeoutMillis = 10_000;
  private long requestTimeoutMillis = 10_000;
  private long responseTimeoutMillis = 10_000;
  private String proxyHost;
  private Integer proxyPort;
  private String proxyUsername;
  private String proxyPassword;
  private HttpRetryPolicy.Factory retryPolicyFactory = HttpRetryPolicy.Factory.NEVER_RETRY;
  private org.eclipse.jetty.client.HttpClient client;
  private boolean closeClientOnClose = true;

  @Override
  public JettyHttpClientBuilder transport(HttpTransport transport) {
    if (transport == HttpTransport.NAMED_PIPE) {
      throw new IllegalArgumentException(
          "Jetty HTTP client does not support named pipe transport, got: " + transport);
    }
    this.transport = transport;
    return this;
  }

  @Override
  public JettyHttpClientBuilder unixDomainSocketPath(@Nullable String unixDomainSocketPath) {
    this.unixDomainSocketPath = unixDomainSocketPath;
    return this;
  }

  @Override
  public JettyHttpClientBuilder namedPipe(@Nullable String namedPipe) {
    if (namedPipe != null && !namedPipe.isEmpty()) {
      throw new UnsupportedOperationException(
          "Jetty HTTP client does not support named pipe transport");
    }
    return this;
  }

  @Override
  public JettyHttpClientBuilder connectTimeoutMillis(long connectTimeoutMillis) {
    this.connectTimeoutMillis = connectTimeoutMillis;
    return this;
  }

  @Override
  public JettyHttpClientBuilder requestTimeoutMillis(long requestTimeoutMillis) {
    this.requestTimeoutMillis = requestTimeoutMillis;
    return this;
  }

  public JettyHttpClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
    this.responseTimeoutMillis = responseTimeoutMillis;
    return this;
  }

  @Override
  public JettyHttpClientBuilder proxy(String proxyHost, int proxyPort) {
    return proxy(proxyHost, proxyPort, null, null);
  }

  @Override
  public JettyHttpClientBuilder proxy(
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
  public JettyHttpClientBuilder retryPolicyFactory(HttpRetryPolicy.Factory retryPolicyFactory) {
    this.retryPolicyFactory = retryPolicyFactory;
    return this;
  }

  public JettyHttpClientBuilder client(
      org.eclipse.jetty.client.HttpClient client, boolean closeClientOnClose) {
    this.client = client;
    this.closeClientOnClose = closeClientOnClose;
    return this;
  }

  @Override
  public HttpClient build() {
    validate();
    return new JettyHttpClient(
        transport,
        unixDomainSocketPath,
        connectTimeoutMillis,
        requestTimeoutMillis,
        responseTimeoutMillis,
        proxyHost,
        proxyPort,
        proxyUsername,
        proxyPassword,
        retryPolicyFactory,
        client,
        closeClientOnClose);
  }

  private void validate() {
    if (connectTimeoutMillis <= 0 || requestTimeoutMillis <= 0 || responseTimeoutMillis <= 0) {
      throw new IllegalArgumentException("timeouts must be strictly positive");
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
  }
}
