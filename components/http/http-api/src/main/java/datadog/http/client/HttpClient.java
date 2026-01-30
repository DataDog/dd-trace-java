package datadog.http.client;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Abstraction for HTTP clients, providing request execution capabilities.
 * This abstraction is implementation-agnostic and can be backed by either OkHttp
 * or JDK HttpClient.
 *
 * <p>HttpClient instances should be reused across requests for connection pooling.
 */
public interface HttpClient extends Closeable {

  /**
   * Executes an HTTP request synchronously and returns the response.
   * The caller is responsible for closing the response.
   *
   * @param request the request to execute
   * @return the HTTP response
   * @throws IOException if an I/O error occurs
   */
  HttpResponse execute(HttpRequest request) throws IOException;

  /**
   * Closes the client and releases any resources (connection pools, threads, etc.).
   */
  @Override
  void close() throws IOException;

  /**
   * Creates a new builder for constructing HTTP clients.
   *
   * @return a new Builder
   */
  static Builder newBuilder() {
    return HttpProviders.newClientBuilder();
  }

  /**
   * Builder for constructing HttpClient instances with custom configuration.
   */
  interface Builder {

    /**
     * Sets the connect timeout.
     *
     * @param timeout the timeout value
     * @param unit the time unit
     * @return this builder
     */
    Builder connectTimeout(long timeout, TimeUnit unit);

    /**
     * Sets the read timeout.
     *
     * @param timeout the timeout value
     * @param unit the time unit
     * @return this builder
     */
    Builder readTimeout(long timeout, TimeUnit unit);

    /**
     * Sets the write timeout.
     *
     * @param timeout the timeout value
     * @param unit the time unit
     * @return this builder
     */
    Builder writeTimeout(long timeout, TimeUnit unit);

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
     * Configures whether to retry requests on connection failures.
     *
     * @param retry true to retry on connection failure
     * @return this builder
     */
    Builder retryOnConnectionFailure(boolean retry);

    /**
     * Sets the maximum number of concurrent requests.
     *
     * @param maxRequests the maximum number of requests
     * @return this builder
     */
    Builder maxRequests(int maxRequests);

    /**
     * Sets a custom executor for executing requests.
     *
     * @param executor the executor
     * @return this builder
     */
    Builder dispatcher(Executor executor);

    /**
     * Builds the HttpClient with the configured settings.
     *
     * @return the constructed HttpClient
     */
    HttpClient build();
  }
}
