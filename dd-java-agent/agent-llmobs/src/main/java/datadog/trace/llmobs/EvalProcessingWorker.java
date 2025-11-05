package datadog.trace.llmobs;

import static datadog.trace.util.AgentThreadFactory.AgentThread.LLMOBS_EVALS_PROCESSOR;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.llmobs.domain.LLMObsEval;
import datadog.trace.util.queue.BlockingConsumerNonBlockingQueue;
import datadog.trace.util.queue.Queues;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EvalProcessingWorker implements AutoCloseable {

  private static final String EVAL_METRIC_API_DOMAIN = "api";
  private static final String EVAL_METRIC_API_PATH = "api/intake/llm-obs/v1/eval-metric";

  private static final String EVP_SUBDOMAIN_HEADER_NAME = "X-Datadog-EVP-Subdomain";
  private static final String DD_API_KEY_HEADER_NAME = "DD-API-KEY";

  private static final Logger log = LoggerFactory.getLogger(EvalProcessingWorker.class);

  private final BlockingConsumerNonBlockingQueue<LLMObsEval> queue;
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

    Headers headers;
    HttpUrl submissionUrl;
    if (isAgentless) {
      submissionUrl =
          HttpUrl.get(
              "https://"
                  + EVAL_METRIC_API_DOMAIN
                  + "."
                  + config.getSite()
                  + "/"
                  + EVAL_METRIC_API_PATH);
      headers = Headers.of(DD_API_KEY_HEADER_NAME, config.getApiKey());
    } else {
      submissionUrl =
          HttpUrl.get(
              sco.agentUrl.toString()
                  + DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT
                  + EVAL_METRIC_API_PATH);
      headers = Headers.of(EVP_SUBDOMAIN_HEADER_NAME, EVAL_METRIC_API_DOMAIN);
    }

    EvalSerializingHandler serializingHandler =
        new EvalSerializingHandler(queue, flushInterval, timeUnit, submissionUrl, headers);
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

    private final BlockingConsumerNonBlockingQueue<LLMObsEval> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final Moshi moshi;
    private final JsonAdapter<LLMObsEval.Request> evalJsonAdapter;
    private final OkHttpClient httpClient;
    private final HttpUrl submissionUrl;
    private final Headers headers;

    private final List<LLMObsEval> buffer = new ArrayList<>();

    public EvalSerializingHandler(
        final BlockingConsumerNonBlockingQueue<LLMObsEval> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final HttpUrl submissionUrl,
        final Headers headers) {
      this.queue = queue;
      this.moshi = new Moshi.Builder().add(LLMObsEval.class, new LLMObsEval.Adapter()).build();

      this.evalJsonAdapter = moshi.adapter(LLMObsEval.Request.class);
      this.httpClient = new OkHttpClient();
      this.submissionUrl = submissionUrl;
      this.headers = headers;

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
          "eval processor worker exited. submitting evals stopped. unsubmitted evals left: "
              + !queuesAreEmpty());
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

        RequestBody requestBody =
            RequestBody.create(okhttp3.MediaType.parse("application/json"), reqBod);
        Request request =
            new Request.Builder().headers(headers).url(submissionUrl).post(requestBody).build();

        try (okhttp3.Response response =
            OkHttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {

          if (response.isSuccessful()) {
            log.debug("successfully flushed evaluation request with {} evals", this.buffer.size());
            this.buffer.clear();
          } else {
            log.error(
                "Could not submit eval metrics (HTTP code {}) {}",
                response.code(),
                response.body() != null ? response.body().string() : "");
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
