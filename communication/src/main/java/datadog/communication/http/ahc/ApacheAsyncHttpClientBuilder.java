package datadog.communication.http.ahc;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClientFacade;
import datadog.communication.http.client.HttpClientFacadeBuilder;
import datadog.communication.http.client.HttpTransport;
import javax.annotation.Nullable;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;

/** Builder used to configure and create {@link ApacheAsyncHttpClient}. */
public final class ApacheAsyncHttpClientBuilder
    implements HttpClientFacadeBuilder<ApacheAsyncHttpClientBuilder> {
  private HttpTransport transport = HttpTransport.TCP;
  private long connectTimeoutMillis = 10_000;
  private long requestTimeoutMillis = 10_000;
  private long responseTimeoutMillis = 10_000;
  private String proxyHost;
  private Integer proxyPort;
  private String proxyUsername;
  private String proxyPassword;
  private HttpRetryPolicy.Factory retryPolicyFactory = HttpRetryPolicy.Factory.NEVER_RETRY;
  private CloseableHttpAsyncClient client;
  private boolean closeClientOnClose = true;

  @Override
  public ApacheAsyncHttpClientBuilder transport(HttpTransport transport) {
    if (transport != HttpTransport.TCP) {
      throw new IllegalArgumentException(
          "Apache async client supports only TCP transport, got: " + transport);
    }
    this.transport = transport;
    return this;
  }

  @Override
  public ApacheAsyncHttpClientBuilder unixDomainSocketPath(@Nullable String unixDomainSocketPath) {
    if (unixDomainSocketPath != null && !unixDomainSocketPath.isEmpty()) {
      throw new UnsupportedOperationException(
          "Apache async client does not support Unix Domain Socket transport");
    }
    return this;
  }

  @Override
  public ApacheAsyncHttpClientBuilder namedPipe(@Nullable String namedPipe) {
    if (namedPipe != null && !namedPipe.isEmpty()) {
      throw new UnsupportedOperationException(
          "Apache async client does not support named pipe transport");
    }
    return this;
  }

  @Override
  public ApacheAsyncHttpClientBuilder connectTimeoutMillis(long connectTimeoutMillis) {
    this.connectTimeoutMillis = connectTimeoutMillis;
    return this;
  }

  @Override
  public ApacheAsyncHttpClientBuilder requestTimeoutMillis(long requestTimeoutMillis) {
    this.requestTimeoutMillis = requestTimeoutMillis;
    return this;
  }

  public ApacheAsyncHttpClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
    this.responseTimeoutMillis = responseTimeoutMillis;
    return this;
  }

  @Override
  public ApacheAsyncHttpClientBuilder proxy(String proxyHost, int proxyPort) {
    return proxy(proxyHost, proxyPort, null, null);
  }

  @Override
  public ApacheAsyncHttpClientBuilder proxy(
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
  public ApacheAsyncHttpClientBuilder retryPolicyFactory(
      HttpRetryPolicy.Factory retryPolicyFactory) {
    this.retryPolicyFactory = retryPolicyFactory;
    return this;
  }

  public ApacheAsyncHttpClientBuilder client(
      CloseableHttpAsyncClient client, boolean closeClientOnClose) {
    this.client = client;
    this.closeClientOnClose = closeClientOnClose;
    return this;
  }

  @Override
  public HttpClientFacade build() {
    validate();
    return new ApacheAsyncHttpClient(
        transport,
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
    if (transport != HttpTransport.TCP) {
      throw new IllegalArgumentException(
          "Apache async client currently supports TCP transport only");
    }
    if (connectTimeoutMillis <= 0 || requestTimeoutMillis <= 0 || responseTimeoutMillis <= 0) {
      throw new IllegalArgumentException("timeouts must be strictly positive");
    }
    if (proxyHost != null && proxyPort == null) {
      throw new IllegalArgumentException("proxy port must be provided when proxy host is set");
    }
  }
}
