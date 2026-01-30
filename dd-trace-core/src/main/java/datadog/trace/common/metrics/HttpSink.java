package datadog.trace.common.metrics;

import static datadog.communication.http.HttpUtils.prepareRequest;
import static datadog.trace.common.metrics.EventListener.EventType.BAD_PAYLOAD;
import static datadog.trace.common.metrics.EventListener.EventType.DOWNGRADED;
import static datadog.trace.common.metrics.EventListener.EventType.ERROR;
import static datadog.trace.common.metrics.EventListener.EventType.OK;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.common.queue.Queues;
import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
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
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpSink implements Sink, EventListener {

  private static final Logger log = LoggerFactory.getLogger(HttpSink.class);

  private static final long ASYNC_THRESHOLD_LATENCY = SECONDS.toNanos(1);

  private final HttpClient client;
  private final HttpUrl metricsUrl;
  private final List<EventListener> listeners;
  private final MessagePassingQueue<HttpRequest> enqueuedRequests = Queues.spscArrayQueue(16);
  private final AtomicLong lastRequestTime = new AtomicLong();
  private final AtomicLong asyncRequestCounter = new AtomicLong();
  private final boolean bufferingEnabled;
  private final boolean compressionEnabled;
  private final Map<String, String> headers;

  private final AtomicBoolean asyncTaskStarted = new AtomicBoolean(false);
  private volatile AgentTaskScheduler.Scheduled<HttpSink> future;

  public HttpSink(
      HttpClient client,
      String agentUrl,
      String path,
      boolean bufferingEnabled,
      boolean compressionEnabled,
      Map<String, String> headers) {
    this.client = client;
    this.metricsUrl = HttpUrl.parse(agentUrl).resolve(path);
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
      AgentTaskScheduler.Scheduled<HttpSink> future = this.future;
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

  private HttpRequestBody makeRequestBody(ByteBuffer buffer) {
    if (compressionEnabled) {
      return HttpUtils.gzippedMsgpackRequestBodyOf(Collections.singletonList(buffer));
    } else {
      return HttpUtils.msgpackRequestBodyOf(Collections.singletonList(buffer));
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

  private void send(HttpRequest request) {
    long start = System.nanoTime();
    try (final HttpResponse response = client.execute(request)) {
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

  private void handleFailure(HttpResponse response) throws IOException {
    final int code = response.code();
    if (code == 404) {
      onEvent(DOWNGRADED, "could not find endpoint");
    } else if (code >= 400 && code < 500) {
      onEvent(BAD_PAYLOAD, response.bodyAsString());
    } else if (code >= 500) {
      onEvent(ERROR, response.bodyAsString());
    }
  }

  private static final class Sender implements AgentTaskScheduler.Task<HttpSink> {

    private final MessagePassingQueue<HttpRequest> inbox;

    private Sender(MessagePassingQueue<HttpRequest> inbox) {
      this.inbox = inbox;
    }

    @Override
    public void run(HttpSink target) {
      HttpRequest request;
      while ((request = inbox.poll()) != null) {
        target.send(request);
      }
    }
  }
}
