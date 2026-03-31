package datadog.http.client;

import java.util.List;
import javax.annotation.Nullable;

/**
 * This interface is an abstraction for HTTP requests, providing access to URL, method, headers, and
 * body.
 */
public interface HttpRequest {
  /* Common headers names and values widely used in HTTP requests */
  String CONTENT_TYPE = "Content-Type";
  String APPLICATION_JSON = "application/json; charset=utf-8";

  /**
   * Returns the request URL.
   *
   * @return the HttpUrl
   */
  HttpUrl url();

  /**
   * Returns the HTTP method ({@code GET}, {@code POST}, {@code PUT}, etc.).
   *
   * @return the method name
   */
  String method();

  /**
   * Returns the first header value for the given name, or {@code null} if not present.
   *
   * @param name the header name
   * @return the first header value, or {@code null} if not present
   */
  @Nullable
  String header(String name);

  /**
   * Returns all header values for the given name.
   *
   * @param name the header name
   * @return list of header values, an empty list if not present
   */
  List<String> headers(String name);

  /**
   * Returns the request body, or {@code null} if this request has no body (e.g., GET requests).
   *
   * @return the request body, or {@code null} if this request has no body
   */
  @Nullable
  HttpRequestBody body();

  /**
   * Creates a new {@link Builder} for constructing HTTP requests.
   *
   * @return a new builder
   */
  static Builder newBuilder() {
    return HttpProviders.get().newRequestBuilder();
  }

  /** Builder for constructing {@link HttpRequest} instances. */
  interface Builder {
    /**
     * Sets the request URL.
     *
     * @param url the URL
     * @return this builder
     */
    Builder url(HttpUrl url);

    /**
     * Sets the request URL from a {@link String}.
     *
     * @param url the URL string
     * @return this builder
     */
    Builder url(String url);

    /**
     * Sets the request method to {@code GET}. This is the default method if other methods are not
     * set.
     *
     * @return this builder
     */
    Builder get();

    /**
     * Sets the request method to {@code POST} with the given body.
     *
     * @param body the request body
     * @return this builder
     */
    Builder post(HttpRequestBody body);

    /**
     * Sets the request method to {@code PUT} with the given body.
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
     *
     * @param listener the listener to notify of request events or {@code null} to remove any
     *     existing listener.
     * @return this builder
     */
    Builder listener(@Nullable HttpRequestListener listener);

    /**
     * Builds the HttpRequest.
     *
     * @return the constructed HttpRequest
     * @throws IllegalStateException if required fields (like url) are missing
     */
    HttpRequest build();
  }
}
