package datadog.http.client.jdk;

import datadog.http.client.HttpRequestBody;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.zip.GZIPOutputStream;

/**
 * JDK HttpClient-based implementation of HttpRequestBody.
 * Converts HttpRequestBody to java.net.http.HttpRequest.BodyPublisher.
 */
public final class JdkHttpRequestBody implements HttpRequestBody {
  private final HttpRequest.BodyPublisher publisher;

  private JdkHttpRequestBody(HttpRequest.BodyPublisher publisher) {
    this.publisher = publisher;
  }

  /**
   * Unwraps to get the BodyPublisher.
   *
   * @return the BodyPublisher
   */
  HttpRequest.BodyPublisher publisher() {
    return this.publisher;
  }

  @Override
  public long contentLength() {
    return this.publisher.contentLength();
  }

  @Override
  public void writeTo(OutputStream out) throws IOException {
    try {
      this.publisher.subscribe(new OutputStreamSubscriber(out));
    } catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      } else {
        throw new IOException("Failed to write request body", e.getCause());
      }
    }
  }

  private static class OutputStreamSubscriber implements Flow.Subscriber<ByteBuffer> {
    private final OutputStream out;

    private OutputStreamSubscriber(OutputStream out) {
      this.out = out;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      // We can handle whatever you've got
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(ByteBuffer item) {
      try {
        // Check if the byte buffer is backed by an array
        // Write in one pass if it is, byte per byte otherwise
        if (item.hasArray()) {
          byte[] array = item.array();
          this.out.write(array, item.position(), item.limit());
        } else {
          for (int i = item.position(); i < item.limit(); i++) {
            this.out.write(item.get(i));
          }
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void onError(Throwable throwable) {
      // Nothing special to do
    }

    @Override
    public void onComplete() {
      // Nothing special to do
    }
  }

  /**
   * Creates a request body from a String using UTF-8 encoding.
   */
  public static JdkHttpRequestBody ofString(String body) {
    return new JdkHttpRequestBody(BodyPublishers.ofString(body));
  }

  /**
   * Creates a request body from MessagePack-encoded ByteBuffers.
   */
  public static JdkHttpRequestBody ofMsgpack(List<ByteBuffer> buffers) throws IOException {
    Objects.requireNonNull(buffers, "buffers");

    // TODO Check usage
    // TODO Use backed array if available and avoid byte[] allocation

    // Calculate total size
    int totalSize = 0;
    for (ByteBuffer buffer : buffers) {
      totalSize += buffer.remaining();
    }

    // Create byte array from buffers
    ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
    for (ByteBuffer buffer : buffers) {
      byte[] bytes = new byte[buffer.remaining()];
      buffer.duplicate().get(bytes);
      baos.write(bytes);
    }
    byte[] allBytes = baos.toByteArray();

    return new JdkHttpRequestBody(BodyPublishers.ofByteArray(allBytes));
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

    return new JdkHttpRequestBody(BodyPublishers.ofByteArray(compressedBytes));
  }

  // /**
  //  * Creates a builder for multipart form data.
  //  */
  // public static HttpRequestBody.MultipartBuilder multipartBuilder() {
  //   return new JdkMultipartBuilder();
  // }
  //
  // /**
  //  * Multipart form data builder for JDK HttpClient.
  //  * Implements RFC 7578 multipart/form-data format.
  //  */
  // public static final class JdkMultipartBuilder implements HttpRequestBody.MultipartBuilder {
  //
  //   private static final String CRLF = "\r\n";
  //   private final String boundary;
  //   private final List<Part> parts = new ArrayList<>();
  //
  //   JdkMultipartBuilder() {
  //     this.boundary = randomBoundary();
  //   }
  //
  //   // TODO Need to be replaced with a lighter generator
  //   private static String randomBoundary() {
  //       Random rnd = ThreadLocalRandom.current();
  //       long msb = (rnd.nextLong() & 0xffff_ffff_ffff_0fffL) | 0x0000_0000_0000_4000L;
  //       long lsb = (rnd.nextLong() & 0x3fff_ffff_ffff_ffffL) | 0x8000_0000_0000_0000L;
  //       return new UUID(msb, lsb).toString().replace("-", "");
  //   }
  //
  //   @Override
  //   public MultipartBuilder addFormDataPart(String name, String value) {
  //     Objects.requireNonNull(name, "name");
  //     Objects.requireNonNull(value, "value");
  //     parts.add(new StringPart(name, value));
  //     return this;
  //   }
  //
  //   @Override
  //   public MultipartBuilder addFormDataPart(String name, String filename, HttpRequestBody body) {
  //     Objects.requireNonNull(name, "name");
  //     Objects.requireNonNull(filename, "filename");
  //     Objects.requireNonNull(body, "body");
  //     parts.add(new FilePart(name, filename, body));
  //     return this;
  //   }
  //
  //   @Override
  //   public HttpRequestBody build() {
  //     try {
  //       // Build multipart body according to RFC 7578
  //       ByteArrayOutputStream baos = new ByteArrayOutputStream();
  //
  //       for (Part part : parts) {
  //         baos.write(("--" + boundary + CRLF).getBytes(UTF_8));
  //         part.writeTo(baos);
  //         baos.write(CRLF.getBytes(UTF_8));
  //       }
  //
  //       // Final boundary
  //       baos.write(("--" + boundary + "--" + CRLF).getBytes(UTF_8));
  //
  //       byte[] bodyBytes = baos.toByteArray();
  //       HttpRequest.BodyPublisher publisher = BodyPublishers.ofByteArray(bodyBytes);
  //
  //       return new JdkHttpRequestBody(publisher);
  //     } catch (IOException e) {
  //       throw new RuntimeException("Failed to build multipart body", e);
  //     }
  //   }
  //
  //   /**
  //    * Returns the Content-Type for this multipart body.
  //    */
  //   public String contentType() {
  //     return "multipart/form-data; boundary=" + boundary;
  //   }
  //
  //   private interface Part {
  //     void writeTo(OutputStream out) throws IOException;
  //   }
  //
  //   private static final class StringPart implements Part {
  //     private final String name;
  //     private final String value;
  //
  //     StringPart(String name, String value) {
  //       this.name = name;
  //       this.value = value;
  //     }
  //
  //     @Override
  //     public void writeTo(OutputStream out) throws IOException {
  //       out.write(("Content-Disposition: form-data; name=\"" + name + "\"" + CRLF).getBytes(UTF_8));
  //       out.write(CRLF.getBytes(UTF_8));
  //       out.write(value.getBytes(UTF_8));
  //     }
  //   }
  //
  //   private static final class FilePart implements Part {
  //     private final String name;
  //     private final String filename;
  //     private final HttpRequestBody body;
  //
  //     FilePart(String name, String filename, HttpRequestBody body) {
  //       this.name = name;
  //       this.filename = filename;
  //       this.body = body;
  //     }
  //
  //     @Override
  //     public void writeTo(OutputStream out) throws IOException {
  //       out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"" + CRLF).getBytes(UTF_8));
  //       out.write(("Content-Type: application/octet-stream" + CRLF).getBytes(UTF_8));
  //       out.write(CRLF.getBytes(UTF_8));
  //       body.writeTo(out);
  //     }
  //   }
  // }
}
