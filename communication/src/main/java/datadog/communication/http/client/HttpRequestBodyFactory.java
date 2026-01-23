package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequestBody;
import java.lang.reflect.Method;
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
  static HttpRequestBody of(String content) {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      try {
        Class<?> jdkBodyClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequestBody");
        Method ofStringMethod = jdkBodyClass.getMethod("ofString", String.class);
        return (HttpRequestBody) ofStringMethod.invoke(null, content);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK request body", e);
      }
    } else {
      return OkHttpRequestBody.ofString(content);
    }
  }

  /**
   * Creates a request body from MessagePack-encoded ByteBuffers.
   *
   * @param buffers the list of ByteBuffers containing msgpack data
   * @return a new HttpRequestBody
   */
  static HttpRequestBody msgpack(List<ByteBuffer> buffers) {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      try {
        Class<?> jdkBodyClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequestBody");
        Method ofMsgpackMethod = jdkBodyClass.getMethod("ofMsgpack", List.class);
        return (HttpRequestBody) ofMsgpackMethod.invoke(null, buffers);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create msgpack body", e);
      }
    } else {
      return OkHttpRequestBody.ofMsgpack(buffers);
    }
  }

  /**
   * Wraps a request body with gzip compression.
   *
   * @param body the body to compress
   * @return a new HttpRequestBody that compresses the delegate
   */
  static HttpRequestBody gzip(HttpRequestBody body) {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      try {
        Class<?> jdkBodyClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequestBody");
        Method ofGzipMethod = jdkBodyClass.getMethod("ofGzip", HttpRequestBody.class);
        return (HttpRequestBody) ofGzipMethod.invoke(null, body);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create gzip body", e);
      }
    } else {
      return OkHttpRequestBody.ofGzip(body);
    }
  }

  /**
   * Creates a builder for multipart form data.
   *
   * @return a new MultipartBuilder
   */
  static HttpRequestBody.MultipartBuilder multipart() {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      try {
        Class<?> jdkBodyClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequestBody");
        Method multipartBuilderMethod = jdkBodyClass.getMethod("multipartBuilder");
        return (HttpRequestBody.MultipartBuilder) multipartBuilderMethod.invoke(null);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create multipart builder", e);
      }
    } else {
      return OkHttpRequestBody.multipartBuilder();
    }
  }
}
