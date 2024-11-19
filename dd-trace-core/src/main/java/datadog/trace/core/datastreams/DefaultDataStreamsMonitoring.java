package datadog.trace.core.datastreams;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_IN;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_OUT;
import static datadog.trace.core.datastreams.TagsProcessor.DIRECTION_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.MANUAL_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TOPIC_TAG;
import static datadog.trace.core.datastreams.TagsProcessor.TYPE_TAG;
import static datadog.trace.util.AgentThreadFactory.AgentThread.DATA_STREAMS_MONITORING;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Backlog;
import datadog.trace.bootstrap.instrumentation.api.InboxItem;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jctools.queues.MpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultDataStreamsMonitoring implements DataStreamsMonitoring, EventListener {
  private static final Logger log = LoggerFactory.getLogger(DefaultDataStreamsMonitoring.class);

  static final long FEATURE_CHECK_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

  private static final StatsPoint REPORT =
      new StatsPoint(Collections.emptyList(), 0, 0, 0, 0, 0, 0, 0, null);
  private static final StatsPoint POISON_PILL =
      new StatsPoint(Collections.emptyList(), 0, 0, 0, 0, 0, 0, 0, null);

  private final Map<Long, Map<String, StatsBucket>> timeToBucket = new HashMap<>();
  private final MpscArrayQueue<InboxItem> inbox = new MpscArrayQueue<>(1024);
  private final DatastreamsPayloadWriter payloadWriter;
  private final DDAgentFeaturesDiscovery features;
  private final TimeSource timeSource;
  private final long hashOfKnownTags;
  private final Supplier<TraceConfig> traceConfigSupplier;
  private final long bucketDurationNanos;
  private final DataStreamContextInjector injector;
  private final Thread thread;
  private AgentTaskScheduler.Scheduled<DefaultDataStreamsMonitoring> cancellation;
  private volatile long nextFeatureCheck;
  private volatile boolean supportsDataStreams = false;
  private volatile boolean agentSupportsDataStreams = false;
  private volatile boolean configSupportsDataStreams = false;
  private final ConcurrentHashMap<String, SchemaSampler> schemaSamplers;
  private static final ThreadLocal<String> serviceNameOverride = new ThreadLocal<>();

  public DefaultDataStreamsMonitoring(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      TimeSource timeSource,
      Supplier<TraceConfig> traceConfigSupplier) {
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
        traceConfigSupplier,
        config);
  }

  public DefaultDataStreamsMonitoring(
      Sink sink,
      DDAgentFeaturesDiscovery features,
      TimeSource timeSource,
      Supplier<TraceConfig> traceConfigSupplier,
      Config config) {
    this(
        sink,
        features,
        timeSource,
        traceConfigSupplier,
        config.getWellKnownTags(),
        new MsgPackDatastreamsPayloadWriter(
            sink, config.getWellKnownTags(), DDTraceCoreInfo.VERSION, config.getPrimaryTag()),
        Config.get().getDataStreamsBucketDurationNanoseconds());
  }

  public DefaultDataStreamsMonitoring(
      Sink sink,
      DDAgentFeaturesDiscovery features,
      TimeSource timeSource,
      Supplier<TraceConfig> traceConfigSupplier,
      WellKnownTags wellKnownTags,
      DatastreamsPayloadWriter payloadWriter,
      long bucketDurationNanos) {
    this.features = features;
    this.timeSource = timeSource;
    this.traceConfigSupplier = traceConfigSupplier;
    this.hashOfKnownTags = DefaultPathwayContext.getBaseHash(wellKnownTags);
    this.payloadWriter = payloadWriter;
    this.bucketDurationNanos = bucketDurationNanos;
    this.injector = new DataStreamContextInjector(this);

    thread = newAgentThread(DATA_STREAMS_MONITORING, new InboxProcessor());
    sink.register(this);
    schemaSamplers = new ConcurrentHashMap<>();
  }

  @Override
  public void start() {
    if (features.getDataStreamsEndpoint() == null) {
      features.discoverIfOutdated();
    }

    agentSupportsDataStreams = features.supportsDataStreams();
    checkDynamicConfig();

    if (!configSupportsDataStreams) {
      log.debug("Data streams is disabled");
    } else if (!agentSupportsDataStreams) {
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
  public int trySampleSchema(String topic) {
    SchemaSampler sampler = schemaSamplers.computeIfAbsent(topic, t -> new SchemaSampler());
    return sampler.trySample(timeSource.getCurrentTimeMillis());
  }

  @Override
  public boolean canSampleSchema(String topic) {
    SchemaSampler sampler = schemaSamplers.computeIfAbsent(topic, t -> new SchemaSampler());
    return sampler.canSample(timeSource.getCurrentTimeMillis());
  }

  @Override
  public Schema getSchema(String schemaName, SchemaIterator iterator) {
    return SchemaBuilder.getSchema(schemaName, iterator);
  }

  @Override
  public void setProduceCheckpoint(String type, String target) {
    setProduceCheckpoint(type, target, DataStreamsContextCarrier.NoOp.INSTANCE, false);
  }

  @Override
  public void setThreadServiceName(String serviceName) {
    if (serviceName == null) {
      clearThreadServiceName();
      return;
    }

    serviceNameOverride.set(serviceName);
  }

  @Override
  public void clearThreadServiceName() {
    serviceNameOverride.remove();
  }

  private static String getThreadServiceName() {
    return serviceNameOverride.get();
  }

  @Override
  public PathwayContext newPathwayContext() {
    if (configSupportsDataStreams) {
      return new DefaultPathwayContext(timeSource, hashOfKnownTags, getThreadServiceName());
    } else {
      return AgentTracer.NoopPathwayContext.INSTANCE;
    }
  }

  @Override
  public HttpCodec.Extractor extractor(HttpCodec.Extractor delegate) {
    return new DataStreamContextExtractor(
        delegate, timeSource, traceConfigSupplier, hashOfKnownTags, getThreadServiceName());
  }

  @Override
  public DataStreamContextInjector injector() {
    return this.injector;
  }

  @Override
  public void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier) {
    if (span instanceof DDSpan) {
      DefaultPathwayContext pathwayContext =
          DefaultPathwayContext.extract(
              carrier,
              DataStreamsContextCarrierAdapter.INSTANCE,
              this.timeSource,
              this.hashOfKnownTags,
              getThreadServiceName());
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
    inbox.offer(new Backlog(tags, value, timeSource.getCurrentTimeNanos(), getThreadServiceName()));
  }

  @Override
  public void setCheckpoint(
      AgentSpan span,
      LinkedHashMap<String, String> sortedTags,
      long defaultTimestamp,
      long payloadSizeBytes) {
    PathwayContext pathwayContext = span.context().getPathwayContext();
    if (pathwayContext != null) {
      pathwayContext.setCheckpoint(sortedTags, this::add, defaultTimestamp, payloadSizeBytes);
    }
  }

  @Override
  public void setConsumeCheckpoint(String type, String source, DataStreamsContextCarrier carrier) {
    if (type == null || type.isEmpty() || source == null || source.isEmpty()) {
      log.warn("setConsumeCheckpoint should be called with non-empty type and source");
      return;
    }

    AgentSpan span = activeSpan();
    if (span == null) {
      log.warn("SetConsumeCheckpoint is called with no active span");
      return;
    }
    mergePathwayContextIntoSpan(span, carrier);

    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(DIRECTION_TAG, DIRECTION_IN);
    sortedTags.put(MANUAL_TAG, "true");
    sortedTags.put(TOPIC_TAG, source);
    sortedTags.put(TYPE_TAG, type);

    setCheckpoint(span, sortedTags, 0, 0);
  }

  public void setProduceCheckpoint(
      String type, String target, DataStreamsContextCarrier carrier, boolean manualCheckpoint) {
    if (type == null || type.isEmpty() || target == null || target.isEmpty()) {
      log.warn("SetProduceCheckpoint should be called with non-empty type and target");
      return;
    }

    AgentSpan span = activeSpan();
    if (span == null) {
      log.warn("SetProduceCheckpoint is called with no active span");
      return;
    }

    LinkedHashMap<String, String> sortedTags = new LinkedHashMap<>();
    sortedTags.put(DIRECTION_TAG, DIRECTION_OUT);
    if (manualCheckpoint) {
      sortedTags.put(MANUAL_TAG, "true");
    }
    sortedTags.put(TOPIC_TAG, target);
    sortedTags.put(TYPE_TAG, type);

    this.injector.injectPathwayContext(
        span, carrier, DataStreamsContextCarrierAdapter.INSTANCE, sortedTags);
  }

  @Override
  public void setProduceCheckpoint(String type, String target, DataStreamsContextCarrier carrier) {
    setProduceCheckpoint(type, target, carrier, true);
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
  }

  private class InboxProcessor implements Runnable {

    private StatsBucket getStatsBucket(final long timestamp, final String serviceNameOverride) {
      long bucket = currentBucket(timestamp);
      Map<String, StatsBucket> statsBucketMap =
          timeToBucket.computeIfAbsent(bucket, startTime -> new HashMap<>(1));
      return statsBucketMap.computeIfAbsent(
          serviceNameOverride, s -> new StatsBucket(bucket, bucketDurationNanos));
    }

    @Override
    public void run() {
      Thread currentThread = Thread.currentThread();
      while (!currentThread.isInterrupted()) {
        try {
          InboxItem payload = inbox.poll();
          if (payload == null) {
            Thread.sleep(10);
            continue;
          }

          if (payload == REPORT) {
            checkDynamicConfig();

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
              StatsBucket statsBucket =
                  getStatsBucket(
                      statsPoint.getTimestampNanos(), statsPoint.getServiceNameOverride());
              statsBucket.addPoint(statsPoint);
            } else if (payload instanceof Backlog) {
              Backlog backlog = (Backlog) payload;
              StatsBucket statsBucket =
                  getStatsBucket(backlog.getTimestampNanos(), backlog.getServiceNameOverride());
              statsBucket.addBacklog(backlog);
            }
          }
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

    // stats are grouped by time buckets and service names
    Map<String, List<StatsBucket>> includedBuckets = new HashMap<>();
    Iterator<Map.Entry<Long, Map<String, StatsBucket>>> mapIterator =
        timeToBucket.entrySet().iterator();

    while (mapIterator.hasNext()) {
      Map.Entry<Long, Map<String, StatsBucket>> entry = mapIterator.next();
      if (entry.getKey() < currentBucket) {
        mapIterator.remove();
        for (Map.Entry<String, StatsBucket> buckets : entry.getValue().entrySet()) {
          if (!includedBuckets.containsKey(buckets.getKey())) {
            includedBuckets.put(buckets.getKey(), new LinkedList<>());
          }

          includedBuckets.get(buckets.getKey()).add(buckets.getValue());
        }
      }
    }

    if (!includedBuckets.isEmpty()) {
      for (Map.Entry<String, List<StatsBucket>> entry : includedBuckets.entrySet()) {
        if (!entry.getValue().isEmpty()) {
          log.debug("Flushing {} buckets ({})", entry.getValue(), entry.getKey());
          payloadWriter.writePayload(entry.getValue(), entry.getKey());
        }
      }
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

  private void checkDynamicConfig() {
    configSupportsDataStreams = traceConfigSupplier.get().isDataStreamsEnabled();
    supportsDataStreams = agentSupportsDataStreams && configSupportsDataStreams;
  }

  private void checkFeatures() {
    boolean oldValue = agentSupportsDataStreams;

    features.discoverIfOutdated();
    agentSupportsDataStreams = features.supportsDataStreams();
    if (oldValue && !agentSupportsDataStreams && configSupportsDataStreams) {
      log.info("Disabling data streams reporting because it is not supported by the agent");
    } else if (!oldValue && agentSupportsDataStreams && configSupportsDataStreams) {
      log.info("Agent upgrade detected. Enabling data streams because it is now supported");
    } else if (!oldValue && agentSupportsDataStreams && !configSupportsDataStreams) {
      log.info(
          "Agent upgrade detected. Not enabling data streams because it is disabled by config");
    }

    supportsDataStreams = agentSupportsDataStreams && configSupportsDataStreams;

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
