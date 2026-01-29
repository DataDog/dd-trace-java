package datadog.http.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Abstraction for HTTP request bodies, providing content writing capabilities.
 * This abstraction is implementation-agnostic and can be backed by either OkHttp's RequestBody
 * or JDK HttpClient's BodyPublisher.
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
   * Creates a request body from a String using UTF-8 encoding.
   * Content-Type should be set via request headers.
   *
   * @param content the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if content is null
   */
  static HttpRequestBody of(String content) {
    return HttpProviders.requestBodyOfString(content);
  }

  /**
   * Creates a request body from raw bytes.
   * Content-Type should be set via request headers.
   *
   * @param bytes the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if the byte array is null
   */
  static HttpRequestBody of(byte[] bytes) {
    return HttpProviders.requestBodyOfBytes(bytes);
  }

  /**
   * Creates a request body from a list of ByteBuffers.
   * Content-Type should be set via request headers.
   *
   * @param buffers the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if the list of buffers is null
   */
  static HttpRequestBody of(List<ByteBuffer> buffers) {
    return HttpProviders.requestBodyOfByteBuffers(buffers);
  }

  // /**
  //  * Creates a request body from MessagePack-encoded ByteBuffers.
  //  *
  //  * @param buffers the list of ByteBuffers containing msgpack data
  //  * @return a new HttpRequestBody
  //  * @throws NullPointerException if buffers is null
  //  */
  // static HttpRequestBody msgpack(List<ByteBuffer> buffers) {
  //   return HttpRequestBodyFactory.msgpack(buffers);
  // }
  //
  // /**
  //  * Wraps a request body with gzip compression.
  //  *
  //  * @param body the body to compress
  //  * @return a new HttpRequestBody that compresses the delegate
  //  * @throws NullPointerException if body is null
  //  */
  // static HttpRequestBody gzip(HttpRequestBody body) {
  //   return HttpRequestBodyFactory.gzip(body);
  // }
  //
  // /**
  //  * Creates a builder for multipart form data.
  //  *
  //  * @return a new MultipartBuilder
  //  */
  // static MultipartBuilder multipart() {
  //   return HttpRequestBodyFactory.multipart();
  // }
  //
  // /**
  //  * Builder for creating multipart/form-data request bodies.
  //  */
  // interface MultipartBuilder {
  //
  //   /**
  //    * Adds a form data part with a text value.
  //    *
  //    * @param name the field name
  //    * @param value the field value
  //    * @return this builder
  //    */
  //   MultipartBuilder addFormDataPart(String name, String value);
  //
  //   /**
  //    * Adds a form data part with a file.
  //    *
  //    * @param name the field name
  //    * @param filename the filename
  //    * @param body the file body
  //    * @return this builder
  //    */
  //   MultipartBuilder addFormDataPart(String name, String filename, HttpRequestBody body);
  //
  //   /**
  //    * Builds the multipart request body.
  //    *
  //    * @return the constructed HttpRequestBody
  //    */
  //   HttpRequestBody build();
  // }
}
