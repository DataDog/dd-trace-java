package datadog.http.client.okhttp;

import static java.util.Objects.requireNonNull;

import datadog.http.client.HttpRequestBody;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;

/**
 * OkHttp-based implementation of HttpRequestBody. Converts HttpRequestBody to okhttp3.RequestBody.
 */
public final class OkHttpRequestBody implements HttpRequestBody {
  private final okhttp3.RequestBody delegate;

  private OkHttpRequestBody(okhttp3.RequestBody delegate) {
    this.delegate = delegate;
  }

  static HttpRequestBody wrap(RequestBody body) {
    if (body == null) {
      return null;
    }
    return new OkHttpRequestBody(body);
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
   *
   * @param content the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if content is null
   */
  public static OkHttpRequestBody ofString(String content) {
    return new OkHttpRequestBody(RequestBody.create(null, content));
  }

  /**
   * Creates a request body from raw bytes.
   *
   * @param bytes the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if the byte array is null
   */
  public static OkHttpRequestBody ofBytes(byte[] bytes) {
    return new OkHttpRequestBody(RequestBody.create(null, bytes));
  }

  /**
   * Creates a request body from a list of ByteBuffers.
   *
   * @param buffers the string content
   * @return a new HttpRequestBody
   * @throws NullPointerException if the list of buffers is null
   */
  public static OkHttpRequestBody ofByteBuffers(List<ByteBuffer> buffers) {
    requireNonNull(buffers, "buffers");
    return new OkHttpRequestBody(new ByteBufferRequestBody(buffers));
  }

  private static class ByteBufferRequestBody extends RequestBody {

    private static final MediaType MSGPACK = MediaType.get("application/msgpack");

    private final List<ByteBuffer> buffers;

    private ByteBufferRequestBody(List<ByteBuffer> buffers) {
      this.buffers = buffers;
    }

    @Override
    public long contentLength() {
      long length = 0;
      for (ByteBuffer buffer : buffers) {
        length += buffer.remaining();
      }
      return length;
    }

    @Override
    public MediaType contentType() {
      return MSGPACK;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      for (ByteBuffer buffer : buffers) {
        while (buffer.hasRemaining()) {
          sink.write(buffer);
        }
      }
    }
  }

  /** Wraps a request body with gzip compression. */
  public static OkHttpRequestBody ofGzip(HttpRequestBody body) throws IOException {
    requireNonNull(body, "body");

    // Compress the body content
    Buffer buffer = new Buffer();
    try (GZIPOutputStream gzipOut = new GZIPOutputStream(buffer.outputStream())) {
      body.writeTo(gzipOut);
    }
    byte[] compressedBytes = buffer.readByteArray();

    return new OkHttpRequestBody(RequestBody.create(null, compressedBytes));
  }

  /** Adapter to convert HttpRequestBody to OkHttp RequestBody. */
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

  /** OkHttp-based implementation of MultipartBuilder. Wraps okhttp3.MultipartBody.Builder. */
  public static final class MultipartBuilder implements HttpRequestBody.MultipartBuilder {
    private final MultipartBody.Builder delegate;

    public MultipartBuilder() {
      this.delegate = new MultipartBody.Builder().setType(MultipartBody.FORM);
    }

    @Override
    public HttpRequestBody.MultipartBuilder addFormDataPart(String name, String value) {
      requireNonNull(name, "name");
      requireNonNull(value, "value");
      delegate.addFormDataPart(name, value);
      return this;
    }

    @Override
    public HttpRequestBody.MultipartBuilder addFormDataPart(
        String name, String filename, HttpRequestBody body) {
      requireNonNull(name, "name");
      requireNonNull(filename, "filename");
      requireNonNull(body, "body");
      delegate.addFormDataPart(name, filename, toRequestBody(body));
      return this;
    }

    @Override
    public HttpRequestBody.MultipartBuilder addPart(
        Map<String, String> headers, HttpRequestBody body) {
      requireNonNull(headers, "headers");
      requireNonNull(body, "body");
      Headers.Builder headersBuilder = new Headers.Builder();
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        headersBuilder.add(entry.getKey(), entry.getValue());
      }
      delegate.addPart(headersBuilder.build(), toRequestBody(body));
      return this;
    }

    @Override
    public String contentType() {
      return delegate.build().contentType().toString();
    }

    @Override
    public HttpRequestBody build() {
      return new OkHttpRequestBody(delegate.build());
    }

    private static RequestBody toRequestBody(HttpRequestBody body) {
      if (body instanceof OkHttpRequestBody) {
        return ((OkHttpRequestBody) body).unwrap();
      }
      return new HttpRequestBodyAdapter(body);
    }
  }
}
