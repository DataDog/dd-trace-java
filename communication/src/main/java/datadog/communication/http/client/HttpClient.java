package datadog.communication.http.client;

import java.io.Closeable;
import java.io.IOException;

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
    return HttpClientFactory.newBuilder();
  }

  /**
   * Builder for constructing HttpClient instances with custom configuration.
   */
  interface Builder {

    /**
     * Builds the HttpClient with the configured settings.
     *
     * @return the constructed HttpClient
     */
    HttpClient build();
  }
}
