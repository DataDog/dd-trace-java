package datadog.http.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import javax.annotation.Nullable;

/**
 * Abstraction for HTTP URLs, providing URL parsing, building, and manipulation capabilities. This
 * abstraction is implementation-agnostic and can be backed by either OkHttp's HttpUrl or
 * java.net.URI.
 */
public interface HttpUrl {

  /**
   * Returns the complete URL as a string.
   *
   * @return the URL string
   */
  String url();

  /**
   * Returns the scheme (protocol) of this URL.
   *
   * @return the scheme (e.g., "http", "https")
   */
  String scheme();

  /**
   * Returns the host of this URL.
   *
   * @return the host name or IP address
   */
  String host();

  /**
   * Returns the port of this URL. Returns the default port for the scheme if not explicitly set (80
   * for http, 443 for https).
   *
   * @return the port number
   */
  int port();

  /**
   * Resolves a relative URL against this URL.
   *
   * @param path the relative path to resolve
   * @return a new HttpUrl with the resolved path
   */
  HttpUrl resolve(String path);

  /**
   * Returns a builder to modify this URL.
   *
   * @return a new Builder based on this URL
   */
  Builder newBuilder();

  /**
   * Parses a URL string into an HttpUrl.
   *
   * @param url the URL string to parse
   * @return the parsed HttpUrl
   * @throws IllegalArgumentException if the URL is malformed
   * @throws NullPointerException if url is null
   */
  static HttpUrl parse(String url) {
    requireNonNull(url, "url");
    return HttpProviders.httpUrlParse(url);
  }

  /**
   * Creates an HttpUrl from an URI.
   *
   * @param uri the URI to get an HttpUrl from
   * @return the HttpUrl related to the URI
   */
  static HttpUrl from(URI uri) {
    requireNonNull(uri, "uri");
    return HttpProviders.httpUrlFrom(uri);
  }

  /**
   * Creates a new builder for constructing URLs.
   *
   * @return a new Builder
   */
  static Builder builder() {
    return HttpProviders.newUrlBuilder();
  }

  /** Builder for constructing HttpUrl instances. */
  interface Builder {

    /**
     * Sets the scheme (protocol) for the URL.
     *
     * @param scheme the scheme (e.g., "http", "https")
     * @return this builder
     */
    Builder scheme(String scheme);

    /**
     * Sets the host for the URL.
     *
     * @param host the host name or IP address
     * @return this builder
     */
    Builder host(String host);

    /**
     * Sets the port for the URL.
     *
     * @param port the port number
     * @return this builder
     */
    Builder port(int port);

    /**
     * Adds a path segment to the URL.
     *
     * @param segment the path segment to add
     * @return this builder
     */
    Builder addPathSegment(String segment);

    /**
     * Adds a query parameter to the URL.
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return this builder
     */
    Builder addQueryParameter(String name, @Nullable String value);

    /**
     * Builds the HttpUrl.
     *
     * @return the constructed HttpUrl
     * @throws IllegalStateException if required fields are missing
     */
    HttpUrl build();
  }
}
