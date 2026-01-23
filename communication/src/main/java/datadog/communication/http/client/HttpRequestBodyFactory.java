package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequestBody;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Factory for creating HttpRequestBody instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
 *
 * <p>For now, this uses OkHttp implementation. In Phase 2, Task 2.3, this will be enhanced
 * to support JDK HttpClient based on Java version detection and configuration.
 */
final class HttpRequestBodyFactory {

  private HttpRequestBodyFactory() {
    // Utility class
  }

  /**
   * Creates a request body from a String using UTF-8 encoding.
   *
   * @param content the string content
   * @return a new HttpRequestBody
   */
  static HttpRequestBody of(String content) {
    return OkHttpRequestBody.ofString(content);
  }

  /**
   * Creates a request body from MessagePack-encoded ByteBuffers.
   *
   * @param buffers the list of ByteBuffers containing msgpack data
   * @return a new HttpRequestBody
   */
  static HttpRequestBody msgpack(List<ByteBuffer> buffers) {
    return OkHttpRequestBody.ofMsgpack(buffers);
  }

  /**
   * Wraps a request body with gzip compression.
   *
   * @param body the body to compress
   * @return a new HttpRequestBody that compresses the delegate
   */
  static HttpRequestBody gzip(HttpRequestBody body) {
    return OkHttpRequestBody.ofGzip(body);
  }

  /**
   * Creates a builder for multipart form data.
   *
   * @return a new MultipartBuilder
   */
  static HttpRequestBody.MultipartBuilder multipart() {
    return OkHttpRequestBody.multipartBuilder();
  }
}
