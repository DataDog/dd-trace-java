package datadog.communication.http.ahc;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClientFacade;
import datadog.communication.http.client.HttpTransport;
import javax.annotation.Nullable;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;

/** Builder used to configure and create {@link ApacheAsyncHttpClient}. */
public final class ApacheAsyncHttpClientBuilder {
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

  public ApacheAsyncHttpClientBuilder transport(HttpTransport transport) {
    this.transport = transport;
    return this;
  }

  public ApacheAsyncHttpClientBuilder connectTimeoutMillis(long connectTimeoutMillis) {
    this.connectTimeoutMillis = connectTimeoutMillis;
    return this;
  }

  public ApacheAsyncHttpClientBuilder requestTimeoutMillis(long requestTimeoutMillis) {
    this.requestTimeoutMillis = requestTimeoutMillis;
    return this;
  }

  public ApacheAsyncHttpClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
    this.responseTimeoutMillis = responseTimeoutMillis;
    return this;
  }

  public ApacheAsyncHttpClientBuilder proxy(String proxyHost, int proxyPort) {
    return proxy(proxyHost, proxyPort, null, null);
  }

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
