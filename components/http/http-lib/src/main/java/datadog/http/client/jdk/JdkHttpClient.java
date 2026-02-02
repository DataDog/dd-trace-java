package datadog.http.client.jdk;

import static java.net.http.HttpResponse.BodyHandlers.ofInputStream;
import static java.util.Objects.requireNonNull;

import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestListener;
import datadog.http.client.HttpResponse;
import java.io.File;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** JDK HttpClient-based implementation that wraps java.net.http.HttpClient. Requires Java 11+. */
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
    requireNonNull(request, "request");
    if (!(request instanceof JdkHttpRequest)) {
      throw new IllegalArgumentException("HttpRequest must be JdkHttpRequest implementation");
    }

    JdkHttpRequest jdkHttpRequest = (JdkHttpRequest) request;
    var httpRequest = jdkHttpRequest.delegate;
    var listener = jdkHttpRequest.listener;

    try {
      if (listener != null) {
        listener.onRequestStart(request);
      }
      var jdkResponse = this.delegate.send(httpRequest, ofInputStream());
      var response = JdkHttpResponse.wrap(jdkResponse);
      if (listener != null) {
        listener.onRequestEnd(request, response);
      }
      return response;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Request interrupted", e);
    } catch (IOException e) {
      if (listener != null) {
        listener.onRequestFailure(request, e);
      }
      throw e;
    }
  }

  @Override
  public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
    requireNonNull(request, "request");
    if (!(request instanceof JdkHttpRequest)) {
      throw new IllegalArgumentException("HttpRequest must be JdkHttpRequest implementation");
    }

    JdkHttpRequest jdkHttpRequest = (JdkHttpRequest) request;
    java.net.http.HttpRequest httpRequest = jdkHttpRequest.delegate;
    HttpRequestListener listener = jdkHttpRequest.listener;

    if (listener != null) {
      listener.onRequestStart(request);
    }

    return delegate
        .sendAsync(httpRequest, ofInputStream())
        .thenApply(
            jdkResponse -> {
              HttpResponse response = JdkHttpResponse.wrap(jdkResponse);
              if (listener != null) {
                listener.onRequestEnd(request, response);
              }
              return response;
            })
        .exceptionally(
            throwable -> {
              Throwable cause =
                  throwable instanceof CompletionException ? throwable.getCause() : throwable;
              IOException ioException =
                  cause instanceof IOException ? (IOException) cause : new IOException(cause);
              if (listener != null) {
                listener.onRequestFailure(request, ioException);
              }
              throw new CompletionException(ioException);
            });
  }

  /** Builder for JdkHttpClient. */
  public static final class Builder implements HttpClient.Builder {

    private final java.net.http.HttpClient.Builder delegate;
    private File unixDomainSocket;
    private String namedPipe;
    private boolean clearText;

    public Builder() {
      this.delegate = java.net.http.HttpClient.newBuilder();
    }

    Builder(java.net.http.HttpClient.Builder delegate) {
      this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration timeout) {
      delegate.connectTimeout(timeout);
      return this;
    }

    @Override
    public HttpClient.Builder proxy(Proxy proxy) {
      if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
        delegate.proxy(ProxySelector.getDefault());
      } else {
        InetSocketAddress address = (InetSocketAddress) proxy.address();
        delegate.proxy(ProxySelector.of(address));
      }
      return this;
    }

    @Override
    public HttpClient.Builder proxyAuthenticator(String username, String password) {
      delegate.authenticator(
          new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
              if (getRequestorType() == RequestorType.PROXY) {
                return new PasswordAuthentication(
                    username, (password == null ? "" : password).toCharArray());
              }
              return null;
            }
          });
      return this;
    }

    @Override
    public HttpClient.Builder unixDomainSocket(File socketFile) {
      this.unixDomainSocket = socketFile;
      // Unix domain socket support will be implemented in Task 4.3
      // For Java 16+: Use StandardProtocolFamily.UNIX
      // For Java 11-15: Use jnr-unixsocket library
      return this;
    }

    @Override
    public HttpClient.Builder namedPipe(String pipeName) {
      this.namedPipe = pipeName;
      // Named pipe support is Windows-specific and not directly supported by JDK HttpClient
      // Would require custom implementation
      return this;
    }

    @Override
    public HttpClient.Builder clearText(boolean clearText) {
      this.clearText = clearText;
      // JDK HttpClient supports both HTTP and HTTPS by default
      // No special configuration needed for clear text
      return this;
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
      delegate.executor(executor);
      return this;
    }

    @Override
    public HttpClient build() {
      return new JdkHttpClient(delegate.build());
    }
  }
}
