package datadog.http.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Abstraction for HTTP request bodies, providing content writing capabilities. This abstraction is
 * implementation-agnostic and can be backed by either OkHttp's RequestBody or JDK HttpClient's
 * BodyPublisher.
 */
public interface HttpRequestBody {
  /**
   * Returns the content length in bytes, or -1 if unknown (e.g., for gzipped content).
   *
   * @return the content length, or -1 if unknown
   */
  long contentLength();

  /**
   * Writes the body content to the given output stream.
   *
   * @param out the output stream to write to
   * @throws IOException if an I/O error occurs
   */
  void writeTo(OutputStream out) throws IOException;

  /**
   * Creates a request body from a String using UTF-8 encoding. Content-Type should be set via
   * request headers.
   *
   * @param content the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if content is null
   */
  static HttpRequestBody of(String content) {
    return HttpProviders.requestBodyOfString(content);
  }

  /**
   * Creates a request body from raw bytes. Content-Type should be set via request headers.
   *
   * @param bytes the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if the byte array is null
   */
  static HttpRequestBody of(byte[] bytes) {
    return HttpProviders.requestBodyOfBytes(bytes);
  }

  /**
   * Creates a request body from a list of ByteBuffers. Content-Type should be set via request
   * headers.
   *
   * @param buffers the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if the list of buffers is null
   */
  static HttpRequestBody of(List<ByteBuffer> buffers) {
    return HttpProviders.requestBodyOfByteBuffers(buffers);
  }

  /**
   * Wraps a request body with gzip compression. The body is compressed eagerly and the content
   * length reflects the compressed size. Content-Encoding header should be set to "gzip" separately
   * via request headers.
   *
   * @param body the body to compress
   * @return a new gzip-compressed HttpRequestBody
   * @throws NullPointerException if body is null
   */
  static HttpRequestBody gzip(HttpRequestBody body) {
    return HttpProviders.requestBodyGzip(body);
  }

  /**
   * Creates a builder for multipart/form-data request bodies.
   *
   * @return a new MultipartBuilder
   */
  static MultipartBuilder multipart() {
    return HttpProviders.requestBodyMultipart();
  }

  /**
   * Builder for creating multipart/form-data request bodies. Implements RFC 7578
   * multipart/form-data format.
   */
  interface MultipartBuilder {
    /**
     * Adds a form data part with a text value.
     *
     * @param name the field name
     * @param value the field value
     * @return this builder
     * @throws NullPointerException if name or value is null
     */
    MultipartBuilder addFormDataPart(String name, String value);

    /**
     * Adds a form data part with a file attachment.
     *
     * @param name the field name
     * @param filename the filename
     * @param body the file content
     * @return this builder
     * @throws NullPointerException if any argument is null
     */
    MultipartBuilder addFormDataPart(String name, String filename, HttpRequestBody body);

    /**
     * Adds a part with custom headers (advanced usage). Use this when you need full control over
     * part headers.
     *
     * @param headers map of header name to value (e.g., Content-Disposition, Content-Type)
     * @param body the part content
     * @return this builder
     * @throws NullPointerException if headers or body is null
     */
    MultipartBuilder addPart(Map<String, String> headers, HttpRequestBody body);

    /**
     * Returns the Content-Type header value for this multipart body. Includes the boundary
     * parameter required for parsing. Can be called before or after build().
     *
     * @return the content type string (e.g., "multipart/form-data; boundary=...")
     */
    String contentType();

    /**
     * Builds the multipart request body.
     *
     * @return the constructed HttpRequestBody
     */
    HttpRequestBody build();
  }
}
