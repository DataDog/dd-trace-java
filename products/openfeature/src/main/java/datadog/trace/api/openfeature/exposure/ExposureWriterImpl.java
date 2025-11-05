package datadog.trace.api.openfeature.exposure;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.api.openfeature.exposure.dto.ExposureEvent;
import datadog.trace.api.openfeature.exposure.dto.ExposuresRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExposureWriterImpl implements ExposureWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExposureWriterImpl.class);
  private static final int DEFAULT_CAPACITY = 1 << 16; // 65536 elements
  private static final int DEFAULT_FLUSH_INTERVAL_IN_SECONDS = 1;
  private static final int FLUSH_THRESHOLD = 100;

  private final long flushInterval;
  private final TimeUnit timeUnit;
  private final SharedCommunicationObjects sco;
  private final Config config;
  private final MpscBlockingConsumerArrayQueue<ExposureEvent> queue;
  private final ExecutorService serializerThread;

  public ExposureWriterImpl(final SharedCommunicationObjects sco, final Config config) {
    this(DEFAULT_CAPACITY, DEFAULT_FLUSH_INTERVAL_IN_SECONDS, SECONDS, sco, config);
  }

  ExposureWriterImpl(
      final int capacity,
      final long flushInterval,
      final TimeUnit timeUnit,
      final SharedCommunicationObjects sco,
      final Config config) {
    this.queue = new MpscBlockingConsumerArrayQueue<>(capacity);
    this.flushInterval = flushInterval;
    this.timeUnit = timeUnit;
    this.sco = sco;
    this.config = config;
    this.serializerThread =
        Executors.newSingleThreadExecutor(
            r -> {
              final Thread thread = new Thread(r);
              thread.setDaemon(true);
              thread.setName("exposure-serializer-thread");
              return thread;
            });
  }

  @Override
  public void init() {
    final BackendApi backendApi =
        new BackendApiFactory(config, sco).createBackendApi(Intake.EVENT_PLATFORM);
    final Map<String, String> context = new HashMap<>(4);
    context.put("service", config.getServiceName() == null ? "unknown" : config.getServiceName());
    if (config.getEnv() != null) {
      context.put("env", config.getEnv());
    }
    if (config.getVersion() != null) {
      context.put("version", config.getVersion());
    }
    serializerThread.submit(
        new ExposureSerializingHandler(backendApi, queue, flushInterval, timeUnit, context));
  }

  @Override
  public void close() {
    serializerThread.shutdownNow();
  }

  @Override
  public void onExposure(final ExposureEvent event) {
    queue.offer(event);
  }

  private static class ExposureSerializingHandler implements Runnable {
    private final MpscBlockingConsumerArrayQueue<ExposureEvent> queue;
    private final long ticksRequiredToFlush;
    private long lastTicks;

    private final JsonAdapter<ExposuresRequest> jsonAdapter;
    private final BackendApi backendApi;

    private final Map<String, String> context;
    private final ExposureCache cache;

    private final List<ExposureEvent> buffer = new ArrayList<>();

    public ExposureSerializingHandler(
        final BackendApi backendApi,
        final MpscBlockingConsumerArrayQueue<ExposureEvent> queue,
        final long flushInterval,
        final TimeUnit timeUnit,
        final Map<String, String> context) {
      this.queue = queue;
      this.cache = new LRUExposureCache(queue.capacity());
      this.jsonAdapter = new Moshi.Builder().build().adapter(ExposuresRequest.class);
      this.backendApi = backendApi;
      this.context = context;

      this.lastTicks = System.nanoTime();
      this.ticksRequiredToFlush = timeUnit.toNanos(flushInterval);

      LOGGER.debug("starting exposure serializer");
    }

    @Override
    public void run() {
      try {
        runDutyCycle();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      LOGGER.debug("exposure processor worker exited. submitting exposures stopped.");
    }

    private void runDutyCycle() throws InterruptedException {
      final Thread thread = Thread.currentThread();
      while (!thread.isInterrupted()) {
        ExposureEvent event;
        while ((event = queue.poll(100, TimeUnit.MILLISECONDS)) != null) {
          if (addToBuffer(event)) {
            consumeBatch();
            break;
          }
        }
        flushIfNecessary();
      }
    }

    private void consumeBatch() {
      queue.drain(this::addToBuffer, queue.size());
    }

    /** Adds an element to the buffer taking care of duplicated exposures thanks to the LRU cache */
    private boolean addToBuffer(final ExposureEvent event) {
      if (cache.add(event)) {
        buffer.add(event);
        return true;
      }
      return false;
    }

    protected void flushIfNecessary() {
      if (buffer.isEmpty()) {
        return;
      }
      if (shouldFlush()) {
        try {
          final ExposuresRequest exposures = new ExposuresRequest(this.context, this.buffer);
          final String reqBod = jsonAdapter.toJson(exposures);
          final RequestBody requestBody =
              RequestBody.create(MediaType.parse("application/json"), reqBod);
          backendApi.post("exposures", requestBody, stream -> null, null, false);
          this.buffer.clear();
        } catch (Exception e) {
          LOGGER.error("Could not submit exposures", e);
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
  }
}
