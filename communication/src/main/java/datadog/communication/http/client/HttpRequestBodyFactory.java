package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequestBody;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Factory for creating HttpRequestBody instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
 */
final class HttpRequestBodyFactory {

  // Cached JDK HttpRequestBody class and methods (loaded via reflection on Java 11+)
  private static final Class<?> JDK_BODY_CLASS;
  private static final Method JDK_BODY_OF_STRING_METHOD;
  private static final Method JDK_BODY_OF_MSGPACK_METHOD;
  private static final Method JDK_BODY_OF_GZIP_METHOD;
  private static final Method JDK_BODY_MULTIPART_BUILDER_METHOD;

  static {
    Class<?> bodyClass = null;
    Method ofStringMethod = null;
    Method ofMsgpackMethod = null;
    Method ofGzipMethod = null;
    Method multipartBuilderMethod = null;
    try {
      bodyClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequestBody");
      ofStringMethod = bodyClass.getMethod("ofString", String.class);
      ofMsgpackMethod = bodyClass.getMethod("ofMsgpack", List.class);
      ofGzipMethod = bodyClass.getMethod("ofGzip", HttpRequestBody.class);
      multipartBuilderMethod = bodyClass.getMethod("multipartBuilder");
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      // JDK HttpRequestBody not available
    }
    JDK_BODY_CLASS = bodyClass;
    JDK_BODY_OF_STRING_METHOD = ofStringMethod;
    JDK_BODY_OF_MSGPACK_METHOD = ofMsgpackMethod;
    JDK_BODY_OF_GZIP_METHOD = ofGzipMethod;
    JDK_BODY_MULTIPART_BUILDER_METHOD = multipartBuilderMethod;
  }

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
    if (HttpClientFactory.isUsingJdkImplementation()) {
      if (JDK_BODY_OF_STRING_METHOD == null) {
        throw new RuntimeException("JDK HttpRequestBody not available");
      }
      try {
        // Use cached reflection to call JdkHttpRequestBody.ofString()
        return (HttpRequestBody) JDK_BODY_OF_STRING_METHOD.invoke(null, content);
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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequestBody msgpack(List<ByteBuffer> buffers) {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      if (JDK_BODY_OF_MSGPACK_METHOD == null) {
        throw new RuntimeException("JDK HttpRequestBody not available");
      }
      try {
        // Use cached reflection to call JdkHttpRequestBody.ofMsgpack()
        return (HttpRequestBody) JDK_BODY_OF_MSGPACK_METHOD.invoke(null, buffers);
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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequestBody gzip(HttpRequestBody body) {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      if (JDK_BODY_OF_GZIP_METHOD == null) {
        throw new RuntimeException("JDK HttpRequestBody not available");
      }
      try {
        // Use cached reflection to call JdkHttpRequestBody.ofGzip()
        return (HttpRequestBody) JDK_BODY_OF_GZIP_METHOD.invoke(null, body);
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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequestBody.MultipartBuilder multipart() {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      if (JDK_BODY_MULTIPART_BUILDER_METHOD == null) {
        throw new RuntimeException("JDK HttpRequestBody not available");
      }
      try {
        // Use cached reflection to call JdkHttpRequestBody.multipartBuilder()
        return (HttpRequestBody.MultipartBuilder) JDK_BODY_MULTIPART_BUILDER_METHOD.invoke(null);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create multipart builder", e);
      }
    } else {
      return OkHttpRequestBody.multipartBuilder();
    }
  }
}
