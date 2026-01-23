package datadog.communication.http.jdk;

import datadog.communication.http.client.HttpRequestBody;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

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
   */
  public static JdkHttpRequestBody ofGzip(HttpRequestBody body) throws IOException {
    Objects.requireNonNull(body, "body");

    // Compress the body content
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
      body.writeTo(gzipOut);
    }
    byte[] compressedBytes = baos.toByteArray();

    HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(compressedBytes);

    HttpRequestBody gzipBody = new HttpRequestBody() {
      @Override
      public long contentLength() {
        return compressedBytes.length;
      }

      @Override
      public void writeTo(OutputStream out) throws IOException {
        out.write(compressedBytes);
      }
    };

    return new JdkHttpRequestBody(gzipBody, publisher);
  }

  /**
   * Creates a builder for multipart form data.
   */
  public static HttpRequestBody.MultipartBuilder multipartBuilder() {
    return new JdkMultipartBuilder();
  }

  /**
   * Multipart form data builder for JDK HttpClient.
   * Implements RFC 7578 multipart/form-data format.
   */
  public static final class JdkMultipartBuilder implements HttpRequestBody.MultipartBuilder {

    private static final String CRLF = "\r\n";
    private final String boundary;
    private final List<Part> parts = new ArrayList<>();

    JdkMultipartBuilder() {
      this.boundary = UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public MultipartBuilder addFormDataPart(String name, String value) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(value, "value");
      parts.add(new StringPart(name, value));
      return this;
    }

    @Override
    public MultipartBuilder addFormDataPart(String name, String filename, HttpRequestBody body) {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(filename, "filename");
      Objects.requireNonNull(body, "body");
      parts.add(new FilePart(name, filename, body));
      return this;
    }

    @Override
    public HttpRequestBody build() {
      try {
        // Build multipart body according to RFC 7578
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        for (Part part : parts) {
          baos.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
          part.writeTo(baos);
          baos.write(CRLF.getBytes(StandardCharsets.UTF_8));
        }

        // Final boundary
        baos.write(("--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8));

        byte[] bodyBytes = baos.toByteArray();
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofByteArray(bodyBytes);

        HttpRequestBody multipartBody = new HttpRequestBody() {
          @Override
          public long contentLength() {
            return bodyBytes.length;
          }

          @Override
          public void writeTo(OutputStream out) throws IOException {
            out.write(bodyBytes);
          }
        };

        return new JdkHttpRequestBody(multipartBody, publisher);
      } catch (IOException e) {
        throw new RuntimeException("Failed to build multipart body", e);
      }
    }

    /**
     * Returns the Content-Type for this multipart body.
     */
    public String contentType() {
      return "multipart/form-data; boundary=" + boundary;
    }

    private interface Part {
      void writeTo(OutputStream out) throws IOException;
    }

    private static final class StringPart implements Part {
      private final String name;
      private final String value;

      StringPart(String name, String value) {
        this.name = name;
        this.value = value;
      }

      @Override
      public void writeTo(OutputStream out) throws IOException {
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"" + CRLF).getBytes(StandardCharsets.UTF_8));
        out.write(CRLF.getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
      }
    }

    private static final class FilePart implements Part {
      private final String name;
      private final String filename;
      private final HttpRequestBody body;

      FilePart(String name, String filename, HttpRequestBody body) {
        this.name = name;
        this.filename = filename;
        this.body = body;
      }

      @Override
      public void writeTo(OutputStream out) throws IOException {
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"" + CRLF).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: application/octet-stream" + CRLF).getBytes(StandardCharsets.UTF_8));
        out.write(CRLF.getBytes(StandardCharsets.UTF_8));
        body.writeTo(out);
      }
    }
  }
}
