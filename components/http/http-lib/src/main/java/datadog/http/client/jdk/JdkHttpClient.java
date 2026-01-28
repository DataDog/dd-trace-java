package datadog.http.client.jdk;

import datadog.http.client.HttpClient;
import datadog.http.client.HttpListener;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * JDK HttpClient-based implementation that wraps java.net.http.HttpClient.
 * Requires Java 11+.
 */
public final class JdkHttpClient implements HttpClient {
  private final java.net.http.HttpClient delegate;

  private JdkHttpClient(java.net.http.HttpClient delegate) {
    this.delegate = delegate;
  }

  // /**
  //  * Wraps a java.net.http.HttpClient.
  //  *
  //  * @param jdkHttpClient the JDK HttpClient to wrap
  //  * @return wrapped HttpClient
  //  */
  // public static HttpClient wrap(java.net.http.HttpClient jdkHttpClient) {
  //   if (jdkHttpClient == null) {
  //     return null;
  //   }
  //   return new JdkHttpClient(jdkHttpClient);
  // }
  //
  // /**
  //  * Unwraps to get the underlying java.net.http.HttpClient.
  //  *
  //  * @return the underlying java.net.http.HttpClient
  //  */
  // public java.net.http.HttpClient unwrap() {
  //   return delegate;
  // }

  @Override
  public HttpResponse execute(HttpRequest request) throws IOException {
    Objects.requireNonNull(request, "request");

    if (!(request instanceof JdkHttpRequest)) {
      throw new IllegalArgumentException("HttpRequest must be JdkHttpRequest implementation");
    }

    java.net.http.HttpRequest jdkRequest = ((JdkHttpRequest) request).unwrap();

    try {
      java.net.http.HttpResponse<InputStream> jdkResponse =
          this.delegate.send(jdkRequest, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
      return JdkHttpResponse.wrap(jdkResponse);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Request interrupted", e);
    }
  }

  @Override
  public void close() throws IOException {
    // JDK HttpClient doesn't require explicit closing
    // It manages its own resources and executors
  }

  /**
   * Builder for JdkHttpClient.
   */
  public static final class JdkHttpClientBuilder implements HttpClient.Builder {

    private final java.net.http.HttpClient.Builder delegate;
    private HttpListener eventListener;
    private File unixDomainSocket;
    private String namedPipe;
    private boolean clearText;

    public JdkHttpClientBuilder() {
      this.delegate = java.net.http.HttpClient.newBuilder();
    }

    JdkHttpClientBuilder(java.net.http.HttpClient.Builder delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Builder connectTimeout(long timeout, TimeUnit unit) {
      delegate.connectTimeout(Duration.ofMillis(unit.toMillis(timeout)));
      return this;
    }

    @Override
    public Builder readTimeout(long timeout, TimeUnit unit) {
      // JDK HttpClient doesn't have a separate read timeout
      // It uses the overall request timeout, which we'll set on a per-request basis
      // Store for later use in request builder if needed
      return this;
    }

    @Override
    public Builder writeTimeout(long timeout, TimeUnit unit) {
      // JDK HttpClient doesn't have a separate write timeout
      // It uses the overall request timeout, which we'll set on a per-request basis
      return this;
    }

    @Override
    public Builder proxy(Proxy proxy) {
      if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
        delegate.proxy(ProxySelector.getDefault());
      } else {
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        delegate.proxy(ProxySelector.of(address));
      }
      return this;
    }

    @Override
    public Builder proxyAuthenticator(String username, String password) {
      delegate.authenticator(new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
          if (getRequestorType() == RequestorType.PROXY) {
            return new PasswordAuthentication(username,
                (password == null ? "" : password).toCharArray());
          }
          return null;
        }
      });
      return this;
    }

    @Override
    public Builder unixDomainSocket(File socketFile) {
      this.unixDomainSocket = socketFile;
      // Unix domain socket support will be implemented in Task 4.3
      // For Java 16+: Use StandardProtocolFamily.UNIX
      // For Java 11-15: Use jnr-unixsocket library
      return this;
    }

    @Override
    public Builder namedPipe(String pipeName) {
      this.namedPipe = pipeName;
      // Named pipe support is Windows-specific and not directly supported by JDK HttpClient
      // Would require custom implementation
      return this;
    }

    @Override
    public Builder clearText(boolean clearText) {
      this.clearText = clearText;
      // JDK HttpClient supports both HTTP and HTTPS by default
      // No special configuration needed for clear text
      return this;
    }

    @Override
    public Builder retryOnConnectionFailure(boolean retry) {
      // JDK HttpClient doesn't have built-in retry mechanism
      // Retry logic should be implemented at application level
      return this;
    }

    @Override
    public Builder maxRequests(int maxRequests) {
      // JDK HttpClient doesn't expose connection pool configuration
      // It manages connections internally
      return this;
    }

    @Override
    public Builder dispatcher(Executor executor) {
      delegate.executor(executor);
      return this;
    }

    @Override
    public Builder eventListener(HttpListener listener) {
      this.eventListener = listener;
      // JDK HttpClient doesn't have an event listener API like OkHttp
      // We'll need to wrap requests to call listener methods
      return this;
    }

    @Override
    public HttpClient build() {
      return new JdkHttpClient(delegate.build());
    }
  }
}
