package datadog.http.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import javax.annotation.Nullable;

/**
 * This interface is an abstraction for HTTP URLs, providing URL parsing, building, and manipulation
 * capabilities. It also offers static factory methods to build URLs from JDK URIs using {@link
 * #from(URI)}, or parse from strings using {@link #parse(String)}.
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
   * @return a new {@link HttpUrl} with the resolved path
   */
  HttpUrl resolve(String path);

  /**
   * Returns a {@link Builder} to modify this URL.
   *
   * @return a new {@link Builder} based on this URL
   */
  Builder newBuilder();

  /**
   * Parses a URL string into an {@link HttpUrl}.
   *
   * @param url the URL string to parse
   * @return the parsed {@link HttpUrl}
   * @throws IllegalArgumentException if the URL is malformed
   */
  static HttpUrl parse(String url) throws IllegalArgumentException {
    requireNonNull(url, "url");
    return HttpProviders.httpUrlParse(url);
  }

  /**
   * Creates an HttpUrl from an {@link URI}.
   *
   * @param uri the {@link URI} to get an {@link HttpUrl} from
   * @return the {@link HttpUrl} related to the URI
   */
  static HttpUrl from(URI uri) {
    requireNonNull(uri, "uri");
    return HttpProviders.httpUrlFrom(uri);
  }

  /**
   * Creates a new {@link Builder}der for constructing URLs.
   *
   * @return a new {@link Builder}
   */
  static Builder builder() {
    return HttpProviders.newUrlBuilder();
  }

  /** Builder for constructing {@link HttpUrl} instances. */
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
