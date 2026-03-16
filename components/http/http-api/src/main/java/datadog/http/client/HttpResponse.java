package datadog.http.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * This interface is an abstraction for HTTP responses, providing access to status code, headers,
 * and body.
 *
 * <p>HttpResponse instances must be closed after use to release resources.
 */
public interface HttpResponse {

  /**
   * Returns the HTTP status code.
   *
   * @return the status code (e.g., 200, 404, 500)
   */
  int code();

  /**
   * Check whether the response code is in [200..300), indicating the request was successful.
   *
   * @return {@code true} if successful, {@code false} otherwise
   */
  boolean isSuccessful();

  /**
   * Returns the first header value for the given name, or {@code null} if not present. Header names
   * are case-insensitive.
   *
   * @param name the header name
   * @return the first header value, or {@code null} if not present
   */
  @Nullable
  String header(String name);

  /**
   * Returns all header values for the given name. Header names are case-insensitive.
   *
   * @param name the header name
   * @return list of header values, an empty list if not present
   */
  List<String> headers(String name);

  /**
   * Returns all header names in this response. Header names are returned in their canonical form.
   *
   * @return set of header names, empty if no headers present
   */
  Set<String> headerNames();

  /**
   * Returns the response body as an {@link InputStream}. The caller is responsible for closing the
   * stream.
   *
   * @return the response body stream
   */
  InputStream body();

  /**
   * Returns the response body as a {@link String} using {@code Content-Type} charset or UTF-8 if
   * absent.
   *
   * @return the response body as a {@link String}
   * @throws IOException if an I/O error occurs
   */
  String bodyAsString() throws IOException;
}
