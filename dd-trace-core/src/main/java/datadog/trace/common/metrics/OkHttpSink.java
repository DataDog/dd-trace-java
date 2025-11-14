package datadog.trace.common.metrics;

import static datadog.communication.http.OkHttpUtils.gzippedMsgpackRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.msgpackRequestBodyOf;
import static datadog.communication.http.OkHttpUtils.prepareRequest;
import static datadog.trace.common.metrics.EventListener.EventType.BAD_PAYLOAD;
import static datadog.trace.common.metrics.EventListener.EventType.DOWNGRADED;
import static datadog.trace.common.metrics.EventListener.EventType.ERROR;
import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.NonBlockingQueue;
import datadog.common.queue.Queues;
import datadog.trace.util.AgentTaskScheduler;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OkHttpSink implements Sink, EventListener {

  private static final Logger log = LoggerFactory.getLogger(OkHttpSink.class);

  private static final long ASYNC_THRESHOLD_LATENCY = SECONDS.toNanos(1);

  private final OkHttpClient client;
  private final HttpUrl metricsUrl;
  private final List<EventListener> listeners;
  private final NonBlockingQueue<Request> enqueuedRequests = Queues.spscArrayQueue(16);
  private final AtomicLong lastRequestTime = new AtomicLong();
  private final AtomicLong asyncRequestCounter = new AtomicLong();
  private final boolean bufferingEnabled;
  private final boolean compressionEnabled;
  private final Map<String, String> headers;

  private final AtomicBoolean asyncTaskStarted = new AtomicBoolean(false);
  private volatile AgentTaskScheduler.Scheduled<OkHttpSink> future;

  public OkHttpSink(
      OkHttpClient client,
      String agentUrl,
      String path,
      boolean bufferingEnabled,
      boolean compressionEnabled,
      Map<String, String> headers) {
    this.client = client;
    this.metricsUrl = HttpUrl.get(agentUrl).resolve(path);
    this.listeners = new CopyOnWriteArrayList<>();
    this.bufferingEnabled = bufferingEnabled;
    this.compressionEnabled = compressionEnabled;
    this.headers = new HashMap<>(headers);

    if (compressionEnabled) {
      this.headers.put("Content-Encoding", "gzip");
    }
  }

  @Override
  public void accept(int messageCount, ByteBuffer buffer) {
    // if the agent is healthy, then we can send on this thread,
    // without copying the buffer, otherwise this needs to be async,
    // so need to copy and buffer the request, and let it be executed
    // on the main task scheduler as a last resort
    if (!bufferingEnabled || lastRequestTime.get() < ASYNC_THRESHOLD_LATENCY) {
      send(prepareRequest(metricsUrl, headers).post(makeRequestBody(buffer)).build());
      AgentTaskScheduler.Scheduled<OkHttpSink> future = this.future;
      if (future != null && enqueuedRequests.isEmpty()) {
        // async mode has been started but request latency is normal,
        // there is no pending work, so switch off async mode
        future.cancel();
        asyncTaskStarted.set(false);
      }
    } else {
      if (asyncTaskStarted.compareAndSet(false, true)) {
        this.future =
            AgentTaskScheduler.get()
                .scheduleAtFixedRate(new Sender(enqueuedRequests), this, 1, 1, SECONDS);
      }
      sendAsync(messageCount, buffer);
    }
  }

  private RequestBody makeRequestBody(ByteBuffer buffer) {
    if (compressionEnabled) {
      return gzippedMsgpackRequestBodyOf(Collections.singletonList(buffer));
    } else {
      return msgpackRequestBodyOf(Collections.singletonList(buffer));
    }
  }

  private void sendAsync(int messageCount, ByteBuffer buffer) {
    asyncRequestCounter.getAndIncrement();
    if (!enqueuedRequests.offer(
        prepareRequest(metricsUrl, headers).post(makeRequestBody(buffer.duplicate())).build())) {
      log.debug(
          "dropping payload of {} and {}B because sending queue was full",
          messageCount,
          buffer.limit());
    }
  }

  public boolean isInDegradedMode() {
    return asyncTaskStarted.get();
  }

  public long asyncRequestCount() {
    return asyncRequestCounter.get();
  }

  private void send(Request request) {
    long start = System.nanoTime();
    try (final okhttp3.Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        handleFailure(response);
      } else {
        onEvent(OK, "");
      }
    } catch (IOException e) {
      onEvent(ERROR, e.getMessage());
    } finally {
      lastRequestTime.set(System.nanoTime() - start);
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

  private static final class Sender implements AgentTaskScheduler.Task<OkHttpSink> {

    private final NonBlockingQueue<Request> inbox;

    private Sender(NonBlockingQueue<Request> inbox) {
      this.inbox = inbox;
    }

    @Override
    public void run(OkHttpSink target) {
      Request request;
      while ((request = inbox.poll()) != null) {
        target.send(request);
      }
    }
  }
}
