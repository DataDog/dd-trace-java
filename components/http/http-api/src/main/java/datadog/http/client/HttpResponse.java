package datadog.http.client;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Abstraction for HTTP responses, providing access to status code, headers, and body. This
 * abstraction is implementation-agnostic and can be backed by either OkHttp's Response or JDK
 * HttpClient's HttpResponse.
 *
 * <p>HttpResponse instances must be closed after use to release resources.
 */
public interface HttpResponse extends Closeable {

  /**
   * Returns the HTTP status code.
   *
   * @return the status code (e.g., 200, 404, 500)
   */
  int code();

  /**
   * Returns true if the response code is in [200..300), indicating the request was successful.
   *
   * @return true if successful, false otherwise
   */
  boolean isSuccessful();

  /**
   * Returns the first header value for the given name, or null if not present. Header names are
   * case-insensitive.
   *
   * @param name the header name
   * @return the first header value, or null
   */
  String header(String name);

  /**
   * Returns all header values for the given name. Header names are case-insensitive.
   *
   * @param name the header name
   * @return list of header values, empty if not present
   */
  List<String> headers(String name);

  /**
   * Returns all header names in this response. Header names are returned in their canonical form.
   *
   * @return set of header names, empty if no headers present
   */
  Set<String> headerNames();

  /**
   * Returns the response body as an InputStream. The caller is responsible for closing the stream.
   *
   * @return the response body stream
   */
  InputStream body();

  /**
   * Returns the response body as a String using Content-Type charset or UTF-8 if absent.
   *
   * @return the response body as a String
   * @throws IOException if an I/O error occurs
   */
  String bodyAsString() throws IOException;

  // TODO Not sure if it's the response that should be closed, or the response body.
  /**
   * Closes the response and releases any resources. This method should be called after the response
   * is no longer needed.
   */
  @Override
  void close();
}
