package datadog.http.client.okhttp;

import datadog.http.client.HttpRequestBody;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;

/**
 * OkHttp-based implementation of HttpRequestBody.
 * Converts HttpRequestBody to okhttp3.RequestBody.
 */
public final class OkHttpRequestBody implements HttpRequestBody {
  private final okhttp3.RequestBody delegate;

  private OkHttpRequestBody(okhttp3.RequestBody delegate) {
    this.delegate = delegate;
  }

  /**
   * Unwraps to get the RequestBody.
   *
   * @return the RequestBody
   */
  okhttp3.RequestBody unwrap() {
    return this.delegate;
  }

  @Override
  public long contentLength() {
    try {
      return this.delegate.contentLength();
    } catch (IOException e) {
      return -1;
    }
  }

  @Override
  public void writeTo(OutputStream out) throws IOException {
    Buffer buffer = new Buffer();
    this.delegate.writeTo(buffer);
    buffer.writeTo(out);
  }

  /**
   * Creates a request body from a String using UTF-8 encoding.
   */
  public static OkHttpRequestBody ofString(String body) {
    return new OkHttpRequestBody(RequestBody.create(null, body));
  }

  /**
   * Creates a request body from MessagePack-encoded ByteBuffers.
   */
  public static OkHttpRequestBody ofMsgpack(List<ByteBuffer> buffers) throws IOException {
    Objects.requireNonNull(buffers, "buffers");

    // Calculate total size
    int totalSize = 0;
    for (ByteBuffer buffer : buffers) {
      totalSize += buffer.remaining();
    }

    // Create byte array from buffers
    Buffer okioBuffer = new Buffer();
    for (ByteBuffer buffer : buffers) {
      byte[] bytes = new byte[buffer.remaining()];
      buffer.duplicate().get(bytes);
      okioBuffer.write(bytes);
    }

    byte[] allBytes = okioBuffer.readByteArray();
    return new OkHttpRequestBody(RequestBody.create(null, allBytes));
  }

  /**
   * Wraps a request body with gzip compression.
   */
  public static OkHttpRequestBody ofGzip(HttpRequestBody body) throws IOException {
    Objects.requireNonNull(body, "body");

    // Compress the body content
    Buffer buffer = new Buffer();
    try (GZIPOutputStream gzipOut = new GZIPOutputStream(buffer.outputStream())) {
      body.writeTo(gzipOut);
    }
    byte[] compressedBytes = buffer.readByteArray();

    return new OkHttpRequestBody(RequestBody.create(null, compressedBytes));
  }

  /**
   * Adapter to convert HttpRequestBody to OkHttp RequestBody.
   */
  private static final class HttpRequestBodyAdapter extends RequestBody {
    private final HttpRequestBody body;

    HttpRequestBodyAdapter(HttpRequestBody body) {
      this.body = body;
    }

    @Override
    public MediaType contentType() {
      return null;
    }

    @Override
    public long contentLength() {
      return body.contentLength();
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      body.writeTo(sink.outputStream());
    }
  }
}
