package datadog.trace.common.metrics;

import static datadog.trace.common.metrics.EventListener.EventType.BAD_PAYLOAD;
import static datadog.trace.common.metrics.EventListener.EventType.DOWNGRADED;
import static datadog.trace.common.metrics.EventListener.EventType.ERROR;
import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static datadog.trace.core.http.OkHttpUtils.buildHttpClient;
import static datadog.trace.core.http.OkHttpUtils.prepareRequest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSink;

public final class OkHttpSink implements Sink, EventListener {

  private final OkHttpClient client;
  private final HttpUrl metricsUrl;
  private final List<EventListener> listeners;

  public OkHttpSink(String agentUrl, long timeoutMillis) {
    this(buildHttpClient(HttpUrl.get(agentUrl), timeoutMillis), agentUrl, "v0.5/stats");
  }

  public OkHttpSink(OkHttpClient client, String agentUrl, String path) {
    this.client = client;
    this.metricsUrl = HttpUrl.get(agentUrl).resolve(path);
    this.listeners = new CopyOnWriteArrayList<>();
  }

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {
    try (final okhttp3.Response response =
        client
            .newCall(prepareRequest(metricsUrl).put(new MetricsPayload(buffer)).build())
            .execute()) {
      if (!response.isSuccessful()) {
        handleFailure(response);
      } else {
        onEvent(OK, "");
      }
    } catch (IOException e) {
      onEvent(ERROR, e.getMessage());
    }
  }

  @Override
  public void onEvent(EventListener.EventType eventType, String message) {
    for (EventListener listener : listeners) {
      listener.onEvent(eventType, message);
    }
  }

  @Override
  public void register(EventListener listener) {
    this.listeners.add(listener);
  }

  private void handleFailure(okhttp3.Response response) throws IOException {
    final int code = response.code();
    if (code == 404) {
      onEvent(DOWNGRADED, "could not find endpoint");
    } else if (code >= 400 && code < 500) {
      onEvent(BAD_PAYLOAD, response.body().string());
    } else if (code >= 500) {
      onEvent(ERROR, response.body().string());
    }
  }

  private static final class MetricsPayload extends RequestBody {

    private static final MediaType MSGPACK = MediaType.get("application/msgpack");

    private final ByteBuffer buffer;

    private MetricsPayload(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    @Override
    public long contentLength() {
      return buffer.remaining();
    }

    @Override
    public MediaType contentType() {
      return MSGPACK;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
      sink.write(buffer);
    }
  }
}
