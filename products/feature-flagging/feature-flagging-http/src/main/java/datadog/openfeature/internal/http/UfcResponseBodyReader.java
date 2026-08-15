package datadog.openfeature.internal.http;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.zip.GZIPInputStream;

/** Reads UFC HTTP responses without allowing input size or gzip expansion to grow without limit. */
final class UfcResponseBodyReader {

  private static final int MAX_RESPONSE_BYTES = 10 << 20;
  static final int MAX_COMPRESSED_BYTES = MAX_RESPONSE_BYTES;
  static final int MAX_DECOMPRESSED_BYTES = MAX_RESPONSE_BYTES;
  private static final int CHUNK_SIZE = 8 << 10;

  private UfcResponseBodyReader() {}

  static BodyHandler<byte[]> boundedBodyHandler() {
    return responseInfo -> {
      if (responseInfo.statusCode() != 200) {
        return BodySubscribers.replacing(null);
      }
      final long contentLength =
          responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1);
      return new LimitedByteArraySubscriber(contentLength, MAX_COMPRESSED_BYTES);
    };
  }

  static byte[] decode(final byte[] body, final String contentEncoding) throws IOException {
    if (body == null) {
      return null;
    }
    final boolean gzip = contentEncoding != null && "gzip".equalsIgnoreCase(contentEncoding.trim());
    if (!gzip) {
      if (body.length > MAX_DECOMPRESSED_BYTES) {
        throw new ResponseTooLargeException("decompressed", MAX_DECOMPRESSED_BYTES);
      }
      return body;
    }
    try (InputStream compressed =
            new LimitedInputStream(
                new ByteArrayInputStream(body), MAX_COMPRESSED_BYTES, "compressed");
        InputStream decompressed = new GZIPInputStream(compressed)) {
      return readBounded(decompressed, MAX_DECOMPRESSED_BYTES, "decompressed");
    }
  }

  private static byte[] readBounded(
      final InputStream input, final int limitBytes, final String kind) throws IOException {
    final List<byte[]> chunks = new ArrayList<>();
    int totalBytes = 0;
    boolean endOfInput = false;

    while (totalBytes < limitBytes && !endOfInput) {
      final int chunkSize = Math.min(CHUNK_SIZE, limitBytes - totalBytes);
      byte[] chunk = new byte[chunkSize];
      int chunkBytes = 0;
      while (chunkBytes < chunkSize) {
        final int read = input.read(chunk, chunkBytes, chunkSize - chunkBytes);
        if (read == -1) {
          endOfInput = true;
          break;
        }
        chunkBytes += read;
      }
      if (chunkBytes > 0) {
        if (chunkBytes < chunk.length) {
          chunk = Arrays.copyOf(chunk, chunkBytes);
        }
        chunks.add(chunk);
        totalBytes += chunkBytes;
      }
    }

    if (!endOfInput && input.read() != -1) {
      throw new ResponseTooLargeException(kind, limitBytes);
    }
    return join(chunks, totalBytes);
  }

  private static byte[] join(final List<byte[]> chunks, final int totalBytes) {
    if (chunks.isEmpty()) {
      return new byte[0];
    }
    if (chunks.size() == 1) {
      return chunks.get(0);
    }
    final byte[] body = new byte[totalBytes];
    int offset = 0;
    for (final byte[] chunk : chunks) {
      System.arraycopy(chunk, 0, body, offset, chunk.length);
      offset += chunk.length;
    }
    return body;
  }

  static final class ResponseTooLargeException extends IOException {
    final int limitBytes;

    ResponseTooLargeException(final String kind, final int limitBytes) {
      super("Feature Flagging " + kind + " response exceeds " + limitBytes + " bytes");
      this.limitBytes = limitBytes;
    }
  }

  static final class LimitedInputStream extends FilterInputStream {
    private final int limitBytes;
    private final String kind;
    private int readBytes;

    LimitedInputStream(final InputStream input, final int limitBytes, final String kind) {
      super(input);
      this.limitBytes = limitBytes;
      this.kind = kind;
    }

    @Override
    public int read() throws IOException {
      final int value = super.read();
      if (value != -1 && ++readBytes > limitBytes) {
        throw new ResponseTooLargeException(kind, limitBytes);
      }
      return value;
    }

    @Override
    public int read(final byte[] bytes, final int offset, final int length) throws IOException {
      final int allowed = Math.min(length, limitBytes - readBytes + 1);
      final int read = super.read(bytes, offset, allowed);
      if (read > 0 && (readBytes += read) > limitBytes) {
        throw new ResponseTooLargeException(kind, limitBytes);
      }
      return read;
    }
  }

  private static final class LimitedByteArraySubscriber implements BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final List<byte[]> chunks = new ArrayList<>();
    private final long contentLength;
    private final int limitBytes;
    private Flow.Subscription subscription;
    private int totalBytes;

    private LimitedByteArraySubscriber(final long contentLength, final int limitBytes) {
      this.contentLength = contentLength;
      this.limitBytes = limitBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
      if (this.subscription != null) {
        subscription.cancel();
        return;
      }
      this.subscription = subscription;
      if (contentLength > limitBytes) {
        fail();
        return;
      }
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(final List<ByteBuffer> buffers) {
      if (body.isDone()) {
        return;
      }
      for (final ByteBuffer buffer : buffers) {
        final int bytes = buffer.remaining();
        if (bytes > limitBytes - totalBytes) {
          fail();
          return;
        }
        final byte[] chunk = new byte[bytes];
        buffer.get(chunk);
        chunks.add(chunk);
        totalBytes += bytes;
      }
    }

    @Override
    public void onError(final Throwable error) {
      body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      if (!body.isDone()) {
        body.complete(join(chunks, totalBytes));
      }
    }

    private void fail() {
      subscription.cancel();
      body.completeExceptionally(new ResponseTooLargeException("encoded", limitBytes));
    }
  }
}
