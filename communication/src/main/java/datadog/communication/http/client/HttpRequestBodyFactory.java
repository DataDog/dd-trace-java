package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequestBody;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Factory for creating HttpRequestBody instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequestBody of(String content) {
    // Try JDK HttpClient implementation if available
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        // Use cached reflection to call JdkHttpRequestBody.ofString()
        return (HttpRequestBody) JdkHttpClientSupport.JDK_BODY_OF_STRING_METHOD.invoke(null, content);
      } catch (Exception e) {
        // Fall through to OkHttp implementation
      }
    }

    // Use OkHttp implementation (fallback or default)
    return OkHttpRequestBody.ofString(content);
  }

  /**
   * Creates a request body from MessagePack-encoded ByteBuffers.
   *
   * @param buffers the list of ByteBuffers containing msgpack data
   * @return a new HttpRequestBody
   */
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequestBody msgpack(List<ByteBuffer> buffers) {
    // Try JDK HttpClient implementation if available
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        // Use cached reflection to call JdkHttpRequestBody.ofMsgpack()
        return (HttpRequestBody) JdkHttpClientSupport.JDK_BODY_OF_MSGPACK_METHOD.invoke(null, buffers);
      } catch (Exception e) {
        // Fall through to OkHttp implementation
      }
    }

    // Use OkHttp implementation (fallback or default)
    return OkHttpRequestBody.ofMsgpack(buffers);
  }

  /**
   * Wraps a request body with gzip compression.
   *
   * @param body the body to compress
   * @return a new HttpRequestBody that compresses the delegate
   */
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequestBody gzip(HttpRequestBody body) {
    // Try JDK HttpClient implementation if available
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        // Use cached reflection to call JdkHttpRequestBody.ofGzip()
        return (HttpRequestBody) JdkHttpClientSupport.JDK_BODY_OF_GZIP_METHOD.invoke(null, body);
      } catch (Exception e) {
        // Fall through to OkHttp implementation
      }
    }

    // Use OkHttp implementation (fallback or default)
    return OkHttpRequestBody.ofGzip(body);
  }

  /**
   * Creates a builder for multipart form data.
   *
   * @return a new MultipartBuilder
   */
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequestBody.MultipartBuilder multipart() {
    // Try JDK HttpClient implementation if available
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        // Use cached reflection to call JdkHttpRequestBody.multipartBuilder()
        return (HttpRequestBody.MultipartBuilder) JdkHttpClientSupport.JDK_BODY_MULTIPART_BUILDER_METHOD.invoke(null);
      } catch (Exception e) {
        // Fall through to OkHttp implementation
      }
    }

    // Use OkHttp implementation (fallback or default)
    return OkHttpRequestBody.multipartBuilder();
  }
}
