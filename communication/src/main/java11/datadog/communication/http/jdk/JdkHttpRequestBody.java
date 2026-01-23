package datadog.communication.http.jdk;

import datadog.communication.http.client.HttpRequestBody;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * JDK HttpClient-based implementation of HttpRequestBody.
 * Converts HttpRequestBody to java.net.http.HttpRequest.BodyPublisher.
 */
public final class JdkHttpRequestBody implements HttpRequestBody {

  private final HttpRequestBody delegate;
  private final HttpRequest.BodyPublisher publisher;

  private JdkHttpRequestBody(HttpRequestBody delegate, HttpRequest.BodyPublisher publisher) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  /**
   * Creates a JdkHttpRequestBody that wraps an HttpRequestBody.
   *
   * @param body the HttpRequestBody to wrap
   * @return wrapped body
   */
  public static JdkHttpRequestBody wrap(HttpRequestBody body) throws IOException {
    Objects.requireNonNull(body, "body");

    // Convert HttpRequestBody to BodyPublisher
    // For now, we'll use a simple approach: write to byte array and publish
    // Task 4.4 will implement streaming BodyPublisher for better performance
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    body.writeTo(baos);
    byte[] bytes = baos.toByteArray();

    HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
    return new JdkHttpRequestBody(body, publisher);
  }

  /**
   * Unwraps to get the BodyPublisher.
   *
   * @return the BodyPublisher
   */
  public HttpRequest.BodyPublisher unwrap() {
    return publisher;
  }

  @Override
  public long contentLength() throws IOException {
    return delegate.contentLength();
  }

  @Override
  public void writeTo(OutputStream out) throws IOException {
    delegate.writeTo(out);
  }

  /**
   * Creates a request body from a String using UTF-8 encoding.
   */
  public static JdkHttpRequestBody ofString(String content) {
    Objects.requireNonNull(content, "content");
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);

    HttpRequestBody body = new HttpRequestBody() {
      @Override
      public long contentLength() {
        return bytes.length;
      }

      @Override
      public void writeTo(OutputStream out) throws IOException {
        out.write(bytes);
      }
    };

    return new JdkHttpRequestBody(body, publisher);
  }

  /**
   * Creates a request body from MessagePack-encoded ByteBuffers.
   */
  public static JdkHttpRequestBody ofMsgpack(List<ByteBuffer> buffers) throws IOException {
    Objects.requireNonNull(buffers, "buffers");

    // Calculate total size
    long totalSize = 0;
    for (ByteBuffer buffer : buffers) {
      totalSize += buffer.remaining();
    }
    final long contentLength = totalSize;

    // Create byte array from buffers
    ByteArrayOutputStream baos = new ByteArrayOutputStream((int) totalSize);
    for (ByteBuffer buffer : buffers) {
      byte[] bytes = new byte[buffer.remaining()];
      buffer.duplicate().get(bytes);
      baos.write(bytes);
    }
    byte[] allBytes = baos.toByteArray();

    HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(allBytes);

    HttpRequestBody body = new HttpRequestBody() {
      @Override
      public long contentLength() {
        return contentLength;
      }

      @Override
      public void writeTo(OutputStream out) throws IOException {
        out.write(allBytes);
      }
    };

    return new JdkHttpRequestBody(body, publisher);
  }

  /**
   * Wraps a request body with gzip compression.
   * Note: Actual gzip implementation deferred to Task 4.4.
   */
  public static JdkHttpRequestBody ofGzip(HttpRequestBody body) throws IOException {
    // For now, pass through without gzip
    // Task 4.4 will implement proper gzip compression
    return wrap(body);
  }

  /**
   * Creates a builder for multipart form data.
   * Note: Multipart implementation deferred to Task 4.4.
   */
  public static HttpRequestBody.MultipartBuilder multipartBuilder() {
    throw new UnsupportedOperationException("Multipart bodies not yet implemented for JDK HttpClient - Task 4.4");
  }
}
