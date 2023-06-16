package datadog.trace.core.datastreams;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT;
import static datadog.trace.util.AgentThreadFactory.AgentThread.DATA_STREAMS_MONITORING;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Backlog;
import datadog.trace.bootstrap.instrumentation.api.InboxItem;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.OkHttpSink;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultDataStreamsMonitoring implements DataStreamsMonitoring, EventListener {
  private static final Logger log = LoggerFactory.getLogger(DefaultDataStreamsMonitoring.class);

  static final long DEFAULT_BUCKET_DURATION_NANOS = TimeUnit.SECONDS.toNanos(10);
  static final long FEATURE_CHECK_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

  private static final StatsPoint REPORT = new StatsPoint(Collections.emptyList(), 0, 0, 0, 0, 0);
  private static final StatsPoint POISON_PILL =
      new StatsPoint(Collections.emptyList(), 0, 0, 0, 0, 0);

  private final Map<Long, StatsBucket> timeToBucket = new HashMap<>();
  private final BlockingQueue<InboxItem> inbox = new MpscBlockingConsumerArrayQueue<>(1024);
  private final DatastreamsPayloadWriter payloadWriter;
  private final DDAgentFeaturesDiscovery features;
  private final TimeSource timeSource;
  private final WellKnownTags wellKnownTags;
  private final long bucketDurationNanos;
  private final Thread thread;
  private AgentTaskScheduler.Scheduled<DefaultDataStreamsMonitoring> cancellation;
  private volatile long nextFeatureCheck;
  private volatile boolean supportsDataStreams = false;

  public DefaultDataStreamsMonitoring(
      Config config, SharedCommunicationObjects sharedCommunicationObjects, TimeSource timeSource) {
    this(
        new OkHttpSink(
            sharedCommunicationObjects.okHttpClient,
            sharedCommunicationObjects.agentUrl.toString(),
            V01_DATASTREAMS_ENDPOINT,
            false,
            true,
            Collections.<String, String>emptyMap()),
        sharedCommunicationObjects.featuresDiscovery(config),
        timeSource,
        config);
  }

  public DefaultDataStreamsMonitoring(
      Sink sink, DDAgentFeaturesDiscovery features, TimeSource timeSource, Config config) {
    this(
        sink,
        features,
        timeSource,
        config.getWellKnownTags(),
        new MsgPackDatastreamsPayloadWriter(
            sink, config.getWellKnownTags(), DDTraceCoreInfo.VERSION, config.getPrimaryTag()),
        DEFAULT_BUCKET_DURATION_NANOS);
  }

  public DefaultDataStreamsMonitoring(
      Sink sink,
      DDAgentFeaturesDiscovery features,
      TimeSource timeSource,
      WellKnownTags wellKnownTags,
      DatastreamsPayloadWriter payloadWriter,
      long bucketDurationNanos) {
    this.features = features;
    this.timeSource = timeSource;
    this.wellKnownTags = wellKnownTags;
    this.payloadWriter = payloadWriter;
    this.bucketDurationNanos = bucketDurationNanos;

    thread = newAgentThread(DATA_STREAMS_MONITORING, new InboxProcessor());
    sink.register(this);
  }

  @Override
  public void start() {
    if (features.getDataStreamsEndpoint() == null) {
      features.discoverIfOutdated();
    }

    if (features.supportsDataStreams()) {
      supportsDataStreams = true;
    } else {
      supportsDataStreams = false;
      log.debug("Data streams is disabled or not supported by agent");
    }

    nextFeatureCheck = timeSource.getCurrentTimeNanos() + FEATURE_CHECK_INTERVAL_NANOS;

    cancellation =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            new ReportTask(), this, bucketDurationNanos, bucketDurationNanos, TimeUnit.NANOSECONDS);
    thread.start();
  }

  @Override
  public void add(StatsPoint statsPoint) {
    if (thread.isAlive()) {
      inbox.offer(statsPoint);
    }
  }

  @Override
  public PathwayContext newPathwayContext() {
    return new DefaultPathwayContext(timeSource, wellKnownTags);
  }

  @Override
  public HttpCodec.Extractor decorate(HttpCodec.Extractor extractor) {
    return new DataStreamContextExtractor(extractor, timeSource, wellKnownTags);
  }

  @Override
  public void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier) {
    if (span instanceof DDSpan) {
      DefaultPathwayContext pathwayContext =
          DefaultPathwayContext.extract(
              carrier,
              DataStreamsContextCarrierAdapter.INSTANCE,
              this.timeSource,
              this.wellKnownTags);
      ((DDSpan) span).context().mergePathwayContext(pathwayContext);
    }
  }

  public void trackBacklog(LinkedHashMap<String, String> sortedTags, long value) {
    List<String> tags = new ArrayList<>(sortedTags.size());
    for (Map.Entry<String, String> entry : sortedTags.entrySet()) {
      String tag = TagsProcessor.createTag(entry.getKey(), entry.getValue());
      if (tag == null) {
        continue;
      }
      tags.add(tag);
    }
    inbox.offer(new Backlog(tags, value, timeSource.getCurrentTimeNanos()));
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

  private class InboxProcessor implements Runnable {
    @Override
    public void run() {
      Thread currentThread = Thread.currentThread();
      while (!currentThread.isInterrupted()) {
        try {
          InboxItem payload = inbox.take();

          if (payload == REPORT) {
            if (supportsDataStreams) {
              flush(timeSource.getCurrentTimeNanos());
            } else if (timeSource.getCurrentTimeNanos() >= nextFeatureCheck) {
              checkFeatures();
            }
          } else if (payload == POISON_PILL) {
            if (supportsDataStreams) {
              flush(Long.MAX_VALUE);
            }
            break;
          } else if (supportsDataStreams) {
            if (payload instanceof StatsPoint) {
              StatsPoint statsPoint = (StatsPoint) payload;
              Long bucket = currentBucket(statsPoint.getTimestampNanos());
              StatsBucket statsBucket =
                  timeToBucket.computeIfAbsent(
                      bucket, startTime -> new StatsBucket(startTime, bucketDurationNanos));
              statsBucket.addPoint(statsPoint);
            } else if (payload instanceof Backlog) {
              Backlog backlog = (Backlog) payload;
              Long bucket = currentBucket(backlog.getTimestampNanos());
              StatsBucket statsBucket =
                  timeToBucket.computeIfAbsent(
                      bucket, startTime -> new StatsBucket(startTime, bucketDurationNanos));
              statsBucket.addBacklog(backlog);
            }
          }
        } catch (InterruptedException e) {
          currentThread.interrupt();
        } catch (Exception e) {
          log.debug("Error monitoring data streams", e);
        }
      }
    }
  }

  private long currentBucket(long timestampNanos) {
    return timestampNanos - (timestampNanos % bucketDurationNanos);
  }

  private void flush(long timestampNanos) {
    long currentBucket = currentBucket(timestampNanos);

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

  @Override
  public void clear() {
    timeToBucket.clear();
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
    boolean oldValue = supportsDataStreams;

    features.discoverIfOutdated();
    supportsDataStreams = features.supportsDataStreams();
    if (oldValue && !supportsDataStreams) {
      log.info("Disabling data streams reporting because it is not supported by the agent");
    } else if (!oldValue && supportsDataStreams) {
      log.info("Agent upgrade detected. Enabling data streams because it is now supported");
    }
    nextFeatureCheck = timeSource.getCurrentTimeNanos() + FEATURE_CHECK_INTERVAL_NANOS;
  }

  private static final class ReportTask
      implements AgentTaskScheduler.Task<DefaultDataStreamsMonitoring> {
    @Override
    public void run(DefaultDataStreamsMonitoring target) {
      target.report();
    }
  }
}
