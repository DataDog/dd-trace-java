package datadog.http.client;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Abstraction for HTTP requests, providing access to URL, method, headers, body, and tags.
 * This abstraction is implementation-agnostic and can be backed by either OkHttp's Request
 * or JDK HttpClient's HttpRequest.
 */
public interface HttpRequest {

  /**
   * Returns the request URL.
   *
   * @return the HttpUrl
   */
  HttpUrl url();

  /**
   * Returns the HTTP method (GET, POST, PUT, etc.).
   *
   * @return the method name
   */
  String method();

  /**
   * Returns the first header value for the given name, or null if not present.
   *
   * @param name the header name
   * @return the first header value, or null
   */
  @Nullable
  String header(String name);

  /**
   * Returns all header values for the given name.
   *
   * @param name the header name
   * @return list of header values, empty if not present
   */
  List<String> headers(String name);

  // /**
  //  * Returns the request body, or null if this request has no body (e.g., GET requests).
  //  *
  //  * @return the request body, or null
  //  */
  // @Nullable
  // HttpRequestBody body();

  /**
   * Creates a new builder for constructing HTTP requests.
   *
   * @return a new Builder
   */
  static Builder newBuilder() {
    return HttpProviders.newRequestBuilder();
  }

  /**
   * Builder for constructing HttpRequest instances.
   */
  interface Builder {
    /**
     * Sets the request URL.
     *
     * @param url the URL
     * @return this builder
     */
    Builder url(HttpUrl url);

    /**
     * Sets the request URL from a string.
     *
     * @param url the URL string
     * @return this builder
     */
    Builder url(String url);

    /**
     * Sets the request method to GET.
     *
     * @return this builder
     */
    Builder get();

    /**
     * Sets the request method to POST with the given body.
     *
     * @param body the request body
     * @return this builder
     */
    Builder post(HttpRequestBody body);

    /**
     * Sets the request method to PUT with the given body.
     *
     * @param body the request body
     * @return this builder
     */
    Builder put(HttpRequestBody body);

    /**
     * Sets a header, replacing any existing values for the same name.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    Builder header(String name, String value);

    /**
     * Adds a header without removing existing values for the same name.
     *
     * @param name the header name
     * @param value the header value
     * @return this builder
     */
    Builder addHeader(String name, String value);

    /**
     * Sets the request listener.
     * @param listener the listener to notify of request events or {@code null} to remove any existing listener.
     * @return this builder
     */
    Builder listener(@Nullable HttpRequestListener listener);

    /**
     * Builds the HttpRequest.
     *
     * @return the constructed HttpRequest
     * @throws IllegalStateException if required fields (url, method) are missing
     */
    HttpRequest build();
  }
}
