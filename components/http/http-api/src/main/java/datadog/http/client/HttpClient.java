package datadog.http.client;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Abstraction for HTTP clients, providing request execution capabilities. This abstraction is
 * implementation-agnostic and can be backed by either OkHttp or JDK HttpClient.
 *
 * <p>HttpClient instances should be reused across requests for connection pooling.
 */
public interface HttpClient {

  /**
   * Executes an HTTP request synchronously and returns the response. The caller is responsible for
   * closing the response.
   *
   * @param request the request to execute
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  HttpResponse execute(HttpRequest request) throws IOException;

  /**
   * Executes an HTTP request asynchronously and returns a CompletableFuture. The caller is
   * responsible for closing the response.
   *
   * <p>If the request has an {@link HttpRequestListener} attached, its callbacks will be invoked:
   * {@code onRequestStart} before the request is sent, {@code onRequestEnd} when the response is
   * received, or {@code onRequestFailure} if an error occurs.
   *
   * @param request the request to execute
   * @return a CompletableFuture that completes with the HTTP response
   */
  CompletableFuture<HttpResponse> executeAsync(HttpRequest request);

  /**
   * Creates a new builder for constructing HTTP clients.
   *
   * @return a new Builder
   */
  static Builder newBuilder() {
    return HttpProviders.newClientBuilder();
  }

  /** Builder for constructing HttpClient instances with custom configuration. */
  interface Builder {

    /**
     * Sets the client timeouts, including the connection.
     *
     * @param timeout the timeout duration
     * @return this builder
     */
    Builder connectTimeout(Duration timeout);

    /**
     * Sets the proxy configuration.
     *
     * @param proxy the proxy to use
     * @return this builder
     */
    Builder proxy(Proxy proxy);

    /**
     * Sets proxy authentication credentials.
     *
     * @param username the proxy username
     * @param password the proxy password
     * @return this builder
     */
    Builder proxyAuthenticator(String username, String password);

    /**
     * Configures the client to use a Unix domain socket.
     *
     * @param socketFile the Unix domain socket file
     * @return this builder
     */
    Builder unixDomainSocket(File socketFile);

    /**
     * Configures the client to use a named pipe (Windows).
     *
     * @param pipeName the named pipe name
     * @return this builder
     */
    Builder namedPipe(String pipeName);

    /**
     * Forces clear text (HTTP) connections, disabling TLS.
     *
     * @param clearText true to force HTTP, false to allow HTTPS
     * @return this builder
     */
    Builder clearText(boolean clearText);

    /**
     * Sets a custom executor for executing requests.
     *
     * @param executor the executor
     * @return this builder
     */
    Builder executor(Executor executor);

    /**
     * Builds the HttpClient with the configured settings.
     *
     * @return the constructed HttpClient
     */
    HttpClient build();
  }
}
