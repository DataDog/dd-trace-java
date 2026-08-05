package datadog.trace.llmobs;

import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.common.queue.MessagePassingBlockingQueue;
import datadog.common.queue.Queues;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.util.AgentThreadFactory.AgentThread;
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

/**
 * Batches LLM Observability payloads on a dedicated thread and posts them to an intake endpoint.
 *
 * <p>Evaluations and feedback share this machinery but not an endpoint: evaluations are submitted
 * to {@code v1/eval-metric} and feedback to {@code v2/eval-metric}, so each gets its own instance
 * with its own queue and its own batch.
 *
 * @param <T> the payload type this worker submits
 */
public class LLMObsIntakeWorker<T> implements AutoCloseable {

  private static final String INTAKE_API_DOMAIN = "api";

  private static final String EVP_SUBDOMAIN_HEADER_NAME = "X-Datadog-EVP-Subdomain";
  private static final String DD_API_KEY_HEADER_NAME = "DD-API-KEY";

  private static final Logger log = LoggerFactory.getLogger(LLMObsIntakeWorker.class);

  /** Serializes a whole batch into the request body sent to the intake. */
  public interface BatchSerializer<T> {
    String toJson(List<T> batch);
  }

  private final MessagePassingBlockingQueue<T> queue;
  private final Thread serializerThread;

  public LLMObsIntakeWorker(
      final String payloadDescription,
      final String apiPath,
      final AgentThread agentThread,
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final SharedCommunicationObjects sco,
      final Config config,
      final BatchSerializer<T> serializer) {
    this.queue = Queues.mpscBlockingConsumerArrayQueue(capacity);

    boolean isAgentless = config.isLlmObsAgentlessEnabled();
    if (isAgentless && (config.getApiKey() == null || config.getApiKey().isEmpty())) {
      log.error("Agentless {} submission requires an API key", payloadDescription);
    }

    Headers headers;
    HttpUrl submissionUrl;
    if (isAgentless) {
      submissionUrl =
          HttpUrl.get("https://" + INTAKE_API_DOMAIN + "." + config.getSite() + "/" + apiPath);
      headers = Headers.of(DD_API_KEY_HEADER_NAME, config.getApiKey());
    } else {
      submissionUrl =
          HttpUrl.get(
              sco.agentUrl.toString() + DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT + apiPath);
      headers = Headers.of(EVP_SUBDOMAIN_HEADER_NAME, INTAKE_API_DOMAIN);
    }

    SerializingHandler<T> serializingHandler =
        new SerializingHandler<>(
            payloadDescription, queue, flushInterval, timeUnit, submissionUrl, headers, serializer);
    this.serializerThread = newAgentThread(agentThread, serializingHandler);
  }

  public void start() {
    this.serializerThread.start();
  }

  public boolean addToQueue(final T payload) {
    return queue.offer(payload);
  }

  @Override
  public void close() {
    serializerThread.interrupt();
    try {
      serializerThread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
  }

  public static class SerializingHandler<T> implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SerializingHandler.class);
    private static final int FLUSH_THRESHOLD = 50;

    private final String payloadDescription;
    private final MessagePassingBlockingQueue<T> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final BatchSerializer<T> serializer;
    private final OkHttpClient httpClient;
    private final HttpUrl submissionUrl;
    private final Headers headers;

    private final List<T> buffer = new ArrayList<>();

    public SerializingHandler(
        final String payloadDescription,
        final MessagePassingBlockingQueue<T> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final HttpUrl submissionUrl,
        final Headers headers,
        final BatchSerializer<T> serializer) {
      this.payloadDescription = payloadDescription;
      this.queue = queue;
      this.serializer = serializer;
      this.httpClient = new OkHttpClient();
      this.submissionUrl = submissionUrl;
      this.headers = headers;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      log.debug("starting {} serializer, url={}", payloadDescription, submissionUrl);
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      log.debug(
          "{} processor worker exited. submitting stopped. unsubmitted payloads left: {}",
          payloadDescription,
          !queuesAreEmpty());
    }

    private void runDutyCycle() throws InterruptedException {
      Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        T payload = queue.poll(100, TimeUnit.MILLISECONDS);
        if (payload != null) {
          buffer.add(payload);
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
        HttpRetryPolicy.Factory retryPolicyFactory = new HttpRetryPolicy.Factory(5, 100, 2.0, true);

        String reqBod;
        try {
          reqBod = serializer.toJson(this.buffer);
        } catch (Exception e) {
          // A batch that cannot be serialized will never serialize, so it is dropped rather than
          // retried. Letting this escape would kill the worker and strand every later payload.
          log.error(
              "Could not serialize {} payloads, dropping {} of them",
              payloadDescription,
              this.buffer.size(),
              e);
          this.buffer.clear();
          return;
        }

        RequestBody requestBody =
            RequestBody.create(okhttp3.MediaType.parse("application/json"), reqBod);
        Request request =
            new Request.Builder().headers(headers).url(submissionUrl).post(requestBody).build();

        try (okhttp3.Response response =
            OkHttpUtils.sendWithRetries(httpClient, retryPolicyFactory, request)) {

          if (response.isSuccessful()) {
            log.debug(
                "successfully flushed {} request with {} payloads",
                payloadDescription,
                this.buffer.size());
            this.buffer.clear();
          } else {
            log.error(
                "Could not submit {} (HTTP code {}) {}",
                payloadDescription,
                response.code(),
                response.body() != null ? response.body().string() : "");
          }
        } catch (Exception e) {
          log.error("Could not submit " + payloadDescription, e);
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
