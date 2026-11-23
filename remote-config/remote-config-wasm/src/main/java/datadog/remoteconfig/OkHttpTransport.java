package datadog.remoteconfig;

import com.datadoghq.remoteconfig.poc.HttpReply;
import com.datadoghq.remoteconfig.poc.HttpTransport;
import datadog.trace.util.SizeCheckedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@link HttpTransport} over the tracer's shared {@link OkHttpClient}, so remote-config polls reuse
 * the agent transport (proxy support, UDS, shared connection pool) instead of spawning their own
 * {@code java.net.http} client.
 *
 * <p>The shared client rejects async enqueued calls (its dispatcher runs on {@code
 * RejectingExecutorService}), so requests execute synchronously on this transport's own executor;
 * the returned future still completes off the polling thread as the transport contract requires.
 */
final class OkHttpTransport implements HttpTransport {

  private static final int MAX_BODY = 16 * 1024 * 1024;
  private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");

  /** Daemons; idle threads terminate, so one shared pool serves every poller. */
  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(
          new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
              Thread thread =
                  new Thread(runnable, "dd-remote-config-http-" + counter.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            }
          });

  private final OkHttpClient client;

  OkHttpTransport(OkHttpClient client) {
    this.client = client;
  }

  @Override
  public CompletableFuture<HttpReply> send(
      String method, String url, List<String[]> headers, byte[] body) {
    Request.Builder request = new Request.Builder().url(url);
    if ("GET".equalsIgnoreCase(method) && body.length == 0) {
      request.get();
    } else {
      request.method(method, RequestBody.create(PROTOBUF, body));
    }
    for (String[] header : headers) {
      request.header(header[0], header[1]);
    }
    Call call = client.newCall(request.build());
    CompletableFuture<HttpReply> result = new CompletableFuture<>();
    EXECUTOR.execute(() -> {
      try (Response response = call.execute()) {
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
          result.complete(HttpReply.of(response.code(), new ArrayList<>(), new byte[0]));
          return;
        }
        if (responseBody.contentLength() > MAX_BODY) {
          result.complete(HttpReply.failure("Response exceeds 16 MiB"));
          return;
        }
        byte[] bytes = readBody(responseBody);
        List<String[]> replyHeaders = new ArrayList<>();
        for (int i = 0; i < response.headers().size(); i++) {
          replyHeaders.add(new String[] {response.headers().name(i), response.headers().value(i)});
        }
        result.complete(HttpReply.of(response.code(), replyHeaders, bytes));
      } catch (IOException error) {
        result.complete(HttpReply.failure(error.getMessage()));
      }
    });
    return result;
  }

  /**
   * Reads the response body bounded by {@link #MAX_BODY}: the size check runs on the stream, so an
   * oversized body is rejected at the cap instead of being fully materialized first. The declared
   * content length, when present and sane, pre-sizes the buffer to avoid growth copies.
   */
  private static byte[] readBody(ResponseBody responseBody) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    try (InputStream stream = new SizeCheckedInputStream(responseBody.byteStream(), MAX_BODY)) {
      for (int read; (read = stream.read(chunk)) >= 0; ) {
        buffer.write(chunk, 0, read);
      }
    }
    return buffer.toByteArray();
  }
}
