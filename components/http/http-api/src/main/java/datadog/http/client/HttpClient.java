package datadog.http.client;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * This interface is an abstraction for HTTP clients, providing request execution capabilities. This
 * abstraction is implementation-agnostic and can be backed by third party libraries of the JDK
 * itself.
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
   * Executes an HTTP request asynchronously and returns a {@link CompletableFuture}. The caller is
   * responsible for closing the response.
   *
   * @param request the request to execute
   * @return a CompletableFuture that completes with the HTTP response
   */
  CompletableFuture<HttpResponse> executeAsync(HttpRequest request);

  /**
   * Creates a new {@link Builder} for constructing HTTP clients.
   *
   * @return a new http client builder
   */
  static Builder newBuilder() {
    return HttpProviders.newClientBuilder();
  }

  /** Builder for constructing {@link HttpClient} instances. */
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
     * @param password the proxy password, or {@code null} to use an empty password
     * @return this builder
     */
    Builder proxyAuthenticator(String username, @Nullable String password);

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
     * @param clearText {@code true} to force HTTP, {@code false} to allow HTTPS
     * @return this builder
     */
    Builder clearText(boolean clearText);

    /**
     * Sets a custom executor for executing async requests.
     *
     * @param executor the executor to use for async requests
     * @return this builder
     */
    Builder executor(Executor executor);

    /**
     * Builds the {@link HttpClient} with the configured settings.
     *
     * @return the constructed HttpClient
     */
    HttpClient build();
  }
}
