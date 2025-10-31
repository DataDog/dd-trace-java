package com.datadog.featureflag;

import static datadog.trace.util.AgentThreadFactory.AgentThread.LLMOBS_EVALS_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import com.datadog.featureflag.exposure.ExposureEvent;
import com.datadog.featureflag.exposure.ExposuresRequest;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExposureWriterImpl implements ExposureWriter {

  private static final String EXPOSURES_API_PATH = "api/v2/exposures";
  private static final String EVP_SUBDOMAIN_HEADER_NAME = "X-Datadog-EVP-Subdomain";
  private static final String EVP_SUBDOMAIN_HEADER_VALUE = "event-platform-intake";

  private static final Logger log = LoggerFactory.getLogger(ExposureWriterImpl.class);

  private final MpscBlockingConsumerArrayQueue<ExposureEvent> queue;
  private final Thread serializerThread;

  public ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final HttpUrl agentUrl,
      final Config config) {
    this.queue = new MpscBlockingConsumerArrayQueue<>(capacity);
    final Headers headers = Headers.of(EVP_SUBDOMAIN_HEADER_NAME, EVP_SUBDOMAIN_HEADER_VALUE);
    final HttpUrl url =
        HttpUrl.get(
            agentUrl.toString()
                + DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT
                + EXPOSURES_API_PATH);
    final Map<String, String> context = new HashMap<>();
    context.put("service", config.getServiceName() == null ? "unknown" : config.getServiceName());
    if (config.getEnv() != null) {
      context.put("env", config.getEnv());
    }
    if (config.getVersion() != null) {
      context.put("version", config.getVersion());
    }
    final ExposureSerializingHandler serializer =
        new ExposureSerializingHandler(queue, flushInterval, timeUnit, url, headers, context);
    this.serializerThread = newAgentThread(LLMOBS_EVALS_PROCESSOR, serializer);
  }

  @Override
  public void init() {
    this.serializerThread.start();
  }

  @Override
  public void close() {
    this.serializerThread.interrupt();
  }

  @Override
  public void write(final ExposureEvent event) {
    queue.offer(event);
  }

  private static class ExposureSerializingHandler implements Runnable {
    private static final int FLUSH_THRESHOLD = 50;

    private final MpscBlockingConsumerArrayQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final JsonAdapter<ExposuresRequest> jsonAdapter;
    private final OkHttpClient httpClient;
    private final HttpUrl submissionUrl;
    private final Headers headers;

    private final Map<String, String> context;

    private final List<ExposureEvent> buffer = new ArrayList<>();

    public ExposureSerializingHandler(
        final MpscBlockingConsumerArrayQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final HttpUrl submissionUrl,
        final Headers headers,
        final Map<String, String> context) {
      this.queue = queue;
      this.jsonAdapter = new Moshi.Builder().build().adapter(ExposuresRequest.class);
      this.httpClient = new OkHttpClient();
      this.submissionUrl = submissionUrl;
      this.headers = headers;
      this.context = context;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      log.debug("starting exposure serializer, url={}", submissionUrl);
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      log.debug(
          "exposure processor worker exited. submitting exposures stopped. unsubmitted exposures left: {}",
          !queuesAreEmpty());
    }

    private void runDutyCycle() throws InterruptedException {
      final Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        final ExposureEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
        if (event != null) {
          buffer.add(event);
          consumeBatch();
        }
        flushIfNecessary();
      }
    }

    private void consumeBatch() {
      queue.drain(buffer::add, queue.size());
    }

    protected void flushIfNecessary() {
      if (buffer.isEmpty()) {
        return;
      }
      if (shouldFlush()) {
        final ExposuresRequest exposures = new ExposuresRequest(this.context, this.buffer);
        final HttpRetryPolicy.Factory retryPolicyFactory =
            new HttpRetryPolicy.Factory(5, 100, 2.0, true);
        final String reqBod = jsonAdapter.toJson(exposures);
        final RequestBody requestBody =
            RequestBody.create(okhttp3.MediaType.parse("application/json"), reqBod);
        final Request request =
            new Request.Builder().headers(headers).url(submissionUrl).post(requestBody).build();
        try (okhttp3.Response response =
            OkHttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {

          if (response.isSuccessful()) {
            log.debug("successfully flushed exposures request with {} evals", this.buffer.size());
            this.buffer.clear();
          } else {
            log.error(
                "Could not submit exposures (HTTP code {}) {}",
                response.code(),
                response.body() != null ? response.body().string() : "");
          }
        } catch (Exception e) {
          log.error("Could not submit exposures", e);
        }
      }
    }

    private boolean shouldFlush() {
      long nanoTime = System.nanoTime();
      long ticks = nanoTime - lastTicks;
      if (ticks > ticksRequiredToFlush || queue.size() >= FLUSH_THRESHOLD) {
        lastTicks = nanoTime;
        return true;
      }
      return false;
    }

    protected boolean queuesAreEmpty() {
      return queue.isEmpty();
    }
  }
}
