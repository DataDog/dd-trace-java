package datadog.trace.core.datastreams;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT;
import static datadog.trace.util.AgentThreadFactory.AgentThread.DATA_STREAMS_MONITORING;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.OkHttpSink;
import datadog.trace.common.metrics.Sink;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultDataStreamsCheckpointer
    implements DataStreamsCheckpointer, AutoCloseable, Runnable, EventListener {
  private static final Logger log = LoggerFactory.getLogger(DefaultDataStreamsCheckpointer.class);

  static final long DEFAULT_BUCKET_DURATION_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private static final StatsPoint REPORT = new StatsPoint(null, null, null, 0, 0, 0, 0, 0);
  private static final StatsPoint POISON_PILL = new StatsPoint(null, null, null, 0, 0, 0, 0, 0);

  private final Map<Long, StatsBucket> timeToBucket = new HashMap<>();
  private final BlockingQueue<StatsPoint> inbox = new MpscBlockingConsumerArrayQueue<>(1024);
  private final DatastreamsPayloadWriter payloadWriter;
  private final DDAgentFeaturesDiscovery features;
  private final TimeSource timeSource;
  private final long bucketDurationMillis;
  private final Thread thread;
  private final AgentTaskScheduler.Scheduled<DefaultDataStreamsCheckpointer> cancellation;

  public DefaultDataStreamsCheckpointer(
      Config config, SharedCommunicationObjects sharedCommunicationObjects, TimeSource timeSource) {
    this(
        new OkHttpSink(
            sharedCommunicationObjects.okHttpClient,
            sharedCommunicationObjects.agentUrl.toString(),
            V01_DATASTREAMS_ENDPOINT,
            false,
            true,
            Collections.<String, String>emptyMap()),
        sharedCommunicationObjects.featuresDiscovery,
        timeSource,
        config.getEnv());
  }

  DefaultDataStreamsCheckpointer(
      Sink sink, DDAgentFeaturesDiscovery features, TimeSource timeSource, String env) {
    this(
        sink,
        features,
        timeSource,
        new DatastreamsPayloadWriter(sink, env),
        DEFAULT_BUCKET_DURATION_MILLIS);
  }

  DefaultDataStreamsCheckpointer(
      Sink sink,
      DDAgentFeaturesDiscovery features,
      TimeSource timeSource,
      DatastreamsPayloadWriter payloadWriter,
      long bucketDurationMillis) {
    this.features = features;
    this.timeSource = timeSource;
    this.payloadWriter = payloadWriter;
    this.bucketDurationMillis = bucketDurationMillis;

    thread = newAgentThread(DATA_STREAMS_MONITORING, this);

    if (features.getDataStreamsEndpoint() == null) {
      features.discover();
    }
    if (features.supportsDataStreams()) {
      sink.register(this);
      cancellation =
          AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
              new ReportTask(),
              this,
              bucketDurationMillis,
              bucketDurationMillis,
              TimeUnit.MILLISECONDS);
      thread.start();
    } else {
      cancellation = null;
      log.debug("Data streams is disabled or not supported by agent");
    }
  }

  // With Java 8, this becomes unnecessary
  @Override
  public void accept(StatsPoint statsPoint) {
    if (thread.isAlive()) {
      inbox.offer(statsPoint);
    }
  }

  @Override
  public PathwayContext newPathwayContext() {
    return new DefaultPathwayContext(timeSource);
  }

  @Override
  public <C> PathwayContext extractPathwayContext(
      C carrier, AgentPropagation.BinaryContextVisitor<C> getter) {
    return DefaultPathwayContext.extract(carrier, getter, timeSource);
  }

  @Override
  public void close() {
    if (null != cancellation) {
      cancellation.cancel();
    }

    inbox.offer(POISON_PILL);
    try {
      thread.join(THREAD_JOIN_TIMOUT_MS);
    } catch (InterruptedException ignored) {
    }
    inbox.clear();
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    while (!currentThread.isInterrupted()) {
      try {
        StatsPoint statsPoint = inbox.take();

        if (statsPoint == REPORT) {
          flush(timeSource.getCurrentTimeMillis());
        } else if (statsPoint == POISON_PILL) {
          flush(Long.MAX_VALUE);
          break;
        } else {
          Long bucket = currentBucket(statsPoint.getTimestampMillis());

          // FIXME computeIfAbsent() is not available because Java 7
          // No easy way to have Java 8 in core even though datastreams monitoring is 8+ from
          // DDSketch
          StatsBucket statsBucket = timeToBucket.get(bucket);
          if (statsBucket == null) {
            statsBucket = new StatsBucket(bucket, bucketDurationMillis);
            timeToBucket.put(bucket, statsBucket);
          }

          statsBucket.addPoint(statsPoint);
        }
      } catch (InterruptedException e) {
        currentThread.interrupt();
      } catch (Exception e) {
        log.debug("Error monitoring data streams", e);
      }
    }
  }

  private long currentBucket(long timestampMillis) {
    return timestampMillis - (timestampMillis % bucketDurationMillis);
  }

  private void flush(long timestampMillis) {
    long currentBucket = currentBucket(timestampMillis);

    List<StatsBucket> includedBuckets = new ArrayList<>();
    Iterator<Map.Entry<Long, StatsBucket>> mapIterator = timeToBucket.entrySet().iterator();

    while (mapIterator.hasNext()) {
      Map.Entry<Long, StatsBucket> entry = mapIterator.next();

      if (entry.getKey() < currentBucket) {
        mapIterator.remove();
        includedBuckets.add(entry.getValue());
      }
    }

    if (!includedBuckets.isEmpty()) {
      log.debug("Flushing {} buckets", includedBuckets.size());
      payloadWriter.writePayload(includedBuckets);
    }
  }

  void report() {
    inbox.offer(REPORT);
  }

  @Override
  public void onEvent(EventType eventType, String message) {
    switch (eventType) {
      case DOWNGRADED:
        log.debug("Agent downgrade was detected");
        checkFeatures();
        break;
      case BAD_PAYLOAD:
        log.debug("bad metrics payload sent to trace agent: {}", message);
        break;
      case ERROR:
        log.debug("trace agent errored receiving metrics payload: {}", message);
        break;
      default:
    }
  }

  private void checkFeatures() {
    features.discover();
    if (!features.supportsDataStreams()) {
      log.debug("Disabling data streams reporting because it is not supported by the agent");
      thread.interrupt();
      close();
    }
  }

  private static final class ReportTask
      implements AgentTaskScheduler.Task<DefaultDataStreamsCheckpointer> {
    @Override
    public void run(DefaultDataStreamsCheckpointer target) {
      target.report();
    }
  }
}
