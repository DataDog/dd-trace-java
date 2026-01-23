package datadog.communication.http.okhttp;

import datadog.communication.http.client.HttpRequestBody;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.GzipSink;
import okio.Okio;

/**
 * OkHttp-based implementation of HttpRequestBody that wraps okhttp3.RequestBody.
 */
public final class OkHttpRequestBody implements HttpRequestBody {

  private final RequestBody delegate;

  private OkHttpRequestBody(RequestBody delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  /**
   * Wraps an okhttp3.RequestBody.
   *
   * @param okHttpRequestBody the OkHttp RequestBody to wrap
   * @return wrapped HttpRequestBody
   */
  public static HttpRequestBody wrap(RequestBody okHttpRequestBody) {
    if (okHttpRequestBody == null) {
      return null;
    }
    return new OkHttpRequestBody(okHttpRequestBody);
  }

  /**
   * Unwraps to get the underlying okhttp3.RequestBody.
   *
   * @return the underlying okhttp3.RequestBody
   */
  public RequestBody unwrap() {
    return delegate;
  }

  @Override
  public long contentLength() throws IOException {
    return delegate.contentLength();
  }

  @Override
  public void writeTo(OutputStream out) throws IOException {
    BufferedSink sink = Okio.buffer(Okio.sink(out));
    delegate.writeTo(sink);
    try {
      sink.flush();
    } catch (IllegalStateException e) {
      // Sink was already closed by delegate (e.g., gzip), data is already written
      if (!"closed".equals(e.getMessage())) {
        throw e;
      }
    }
  }

  /**
   * Creates a request body from a String using UTF-8 encoding.
   */
  public static HttpRequestBody ofString(String content) {
    Objects.requireNonNull(content, "content");
    return new OkHttpRequestBody(new StringRequestBody(content));
  }

  /**
   * Creates a request body from MessagePack-encoded ByteBuffers.
   */
  public static HttpRequestBody ofMsgpack(List<ByteBuffer> buffers) {
    Objects.requireNonNull(buffers, "buffers");
    return new OkHttpRequestBody(new MsgpackRequestBody(buffers));
  }

  /**
   * Wraps a request body with gzip compression.
   */
  public static HttpRequestBody ofGzip(HttpRequestBody body) {
    Objects.requireNonNull(body, "body");
    if (body instanceof OkHttpRequestBody) {
      RequestBody delegate = ((OkHttpRequestBody) body).unwrap();
      return new OkHttpRequestBody(new GzipRequestBody(delegate));
    }
    throw new IllegalArgumentException("Cannot gzip non-OkHttp request body");
  }

  /**
   * Creates a builder for multipart form data.
   */
  public static HttpRequestBody.MultipartBuilder multipartBuilder() {
    return new OkHttpMultipartBuilder();
  }

  /**
   * String request body using UTF-8 encoding.
   */
  private static final class StringRequestBody extends RequestBody {

    private final byte[] bytes;

    StringRequestBody(String content) {
      this.bytes = content.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public long contentLength() {
      return bytes.length;
    }

    @Override
    public MediaType contentType() {
      return null; // Content-Type set via request headers
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      sink.write(bytes);
    }
  }

  /**
   * MessagePack request body.
   */
  private static final class MsgpackRequestBody extends RequestBody {

    private static final MediaType MSGPACK = MediaType.get("application/msgpack");

    private final List<ByteBuffer> buffers;

    MsgpackRequestBody(List<ByteBuffer> buffers) {
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

  /**
   * Gzip-compressed request body.
   */
  private static final class GzipRequestBody extends RequestBody {

    private final RequestBody delegate;

    GzipRequestBody(RequestBody delegate) {
      this.delegate = delegate;
    }

    @Override
    public MediaType contentType() {
      return delegate.contentType();
    }

    @Override
    public long contentLength() {
      return -1; // Unknown after compression
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      BufferedSink gzipSink = Okio.buffer(new GzipSink(sink));
      delegate.writeTo(gzipSink);
      gzipSink.close();
    }
  }

  /**
   * Multipart form data builder.
   */
  public static final class OkHttpMultipartBuilder implements HttpRequestBody.MultipartBuilder {

    private final MultipartBody.Builder delegate;

    OkHttpMultipartBuilder() {
      this.delegate = new MultipartBody.Builder().setType(MultipartBody.FORM);
    }

    @Override
    public MultipartBuilder addFormDataPart(String name, String value) {
      delegate.addFormDataPart(name, value);
      return this;
    }

    @Override
    public MultipartBuilder addFormDataPart(String name, String filename, HttpRequestBody body) {
      if (!(body instanceof OkHttpRequestBody)) {
        throw new IllegalArgumentException("Body must be OkHttpRequestBody");
      }
      RequestBody okHttpBody = ((OkHttpRequestBody) body).unwrap();
      delegate.addFormDataPart(name, filename, okHttpBody);
      return this;
    }

    @Override
    public HttpRequestBody build() {
      return new OkHttpRequestBody(delegate.build());
    }
  }
}
