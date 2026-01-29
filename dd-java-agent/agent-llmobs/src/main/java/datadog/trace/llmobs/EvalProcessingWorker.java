package datadog.trace.llmobs;

import static datadog.trace.util.AgentThreadFactory.AgentThread.LLMOBS_EVALS_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.HttpUtils;
import datadog.http.client.HttpClient;
import datadog.http.client.HttpRequest;
import datadog.http.client.HttpRequestBody;
import datadog.http.client.HttpResponse;
import datadog.http.client.HttpUrl;
import datadog.trace.api.Config;
import datadog.trace.llmobs.domain.LLMObsEval;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EvalProcessingWorker implements AutoCloseable {

  private static final String EVAL_METRIC_API_DOMAIN = "api";
  private static final String EVAL_METRIC_API_PATH = "api/intake/llm-obs/v1/eval-metric";

  private static final String EVP_SUBDOMAIN_HEADER_NAME = "X-Datadog-EVP-Subdomain";
  private static final String DD_API_KEY_HEADER_NAME = "DD-API-KEY";

  private static final Logger log = LoggerFactory.getLogger(EvalProcessingWorker.class);

  private final MessagePassingBlockingQueue<LLMObsEval> queue;
  private final Thread serializerThread;

  public EvalProcessingWorker(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final SharedCommunicationObjects sco,
      Config config) {
    this.queue = Queues.mpscBlockingConsumerArrayQueue(capacity);

    boolean isAgentless = config.isLlmObsAgentlessEnabled();
    if (isAgentless && (config.getApiKey() == null || config.getApiKey().isEmpty())) {
      log.error("Agentless eval metric submission requires an API key");
    }

    String headerName;
    String headerValue;
    HttpUrl submissionUrl;
    if (isAgentless) {
      submissionUrl =
          HttpUrl.parse(
              "https://"
                  + EVAL_METRIC_API_DOMAIN
                  + "."
                  + config.getSite()
                  + "/"
                  + EVAL_METRIC_API_PATH);
      headerName = DD_API_KEY_HEADER_NAME;
      headerValue = config.getApiKey();
    } else {
      submissionUrl =
          HttpUrl.parse(
              sco.agentUrl.toString()
                  + DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT
                  + EVAL_METRIC_API_PATH);
      headerName = EVP_SUBDOMAIN_HEADER_NAME;
      headerValue = EVAL_METRIC_API_DOMAIN;
    }

    EvalSerializingHandler serializingHandler =
        new EvalSerializingHandler(queue, flushInterval, timeUnit, submissionUrl, headerName, headerValue);
    this.serializerThread = newAgentThread(LLMOBS_EVALS_PROCESSOR, serializingHandler);
  }

  public void start() {
    this.serializerThread.start();
  }

  public boolean addToQueue(final LLMObsEval eval) {
    return queue.offer(eval);
  }

  @Override
  public void close() {
    serializerThread.interrupt();
    try {
      serializerThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  public static class EvalSerializingHandler implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(EvalSerializingHandler.class);
    private static final int FLUSH_THRESHOLD = 50;

    private final MessagePassingBlockingQueue<LLMObsEval> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final Moshi moshi;
    private final JsonAdapter<LLMObsEval.Request> evalJsonAdapter;
    private final HttpClient httpClient;
    private final HttpUrl submissionUrl;
    private final String headerName;
    private final String headerValue;

    private final List<LLMObsEval> buffer = new ArrayList<>();

    public EvalSerializingHandler(
        final MessagePassingBlockingQueue<LLMObsEval> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final HttpUrl submissionUrl,
        final String headerName,
        final String headerValue) {
      this.queue = queue;
      this.moshi = new Moshi.Builder().add(LLMObsEval.class, new LLMObsEval.Adapter()).build();

      this.evalJsonAdapter = moshi.adapter(LLMObsEval.Request.class);
      this.httpClient = HttpClient.newBuilder().build();
      this.submissionUrl = submissionUrl;
      this.headerName = headerName;
      this.headerValue = headerValue;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      log.debug("starting eval metric serializer, url={}", submissionUrl);
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      log.debug(
          "eval processor worker exited. submitting evals stopped. unsubmitted evals left: {}",
          !queuesAreEmpty());
    }

    private void runDutyCycle() throws InterruptedException {
      Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        LLMObsEval eval = queue.poll(100, TimeUnit.MILLISECONDS);
        if (eval != null) {
          buffer.add(eval);
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
        LLMObsEval.Request llmobsEvalReq = new LLMObsEval.Request(this.buffer);
        HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0, true);

        String reqBod = evalJsonAdapter.toJson(llmobsEvalReq);

        HttpRequest request = HttpRequest.newBuilder()
            .header("Content-Type", "application/json; charset=utf-8")
            .header(headerName, headerValue)
            .url(submissionUrl)
            .post(HttpRequestBody.of(reqBod))
            .build();

        try (HttpResponse response = HttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {
          if (response.isSuccessful()) {
            log.debug("successfully flushed evaluation request with {} evals", this.buffer.size());
            this.buffer.clear();
          } else {
            log.error(
                "Could not submit eval metrics (HTTP code {}) {}",
                response.code(),
                response.bodyAsString());
          }
        } catch (Exception e) {
          log.error("Could not submit eval metrics", e);
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
