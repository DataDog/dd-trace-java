package datadog.trace.core.datastreams;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT;
import static datadog.trace.api.datastreams.DataStreamsContext.fromTags;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.INBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.Direction.OUTBOUND;
import static datadog.trace.api.datastreams.DataStreamsTags.create;
import static datadog.trace.api.datastreams.DataStreamsTags.createManual;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.util.AgentThreadFactory.AgentThread.DATA_STREAMS_MONITORING;
import static datadog.trace.util.AgentThreadFactory.THREAD_JOIN_TIMOUT_MS;
import static datadog.trace.util.AgentThreadFactory.newAgentThread;

import datadog.common.queue.Queues;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.context.propagation.Propagator;
import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.*;
import datadog.trace.api.datastreams.SchemaRegistryUsage;
import datadog.trace.api.experimental.DataStreamsContextCarrier;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Schema;
import datadog.trace.bootstrap.instrumentation.api.SchemaIterator;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.OkHttpSink;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultDataStreamsMonitoring implements DataStreamsMonitoring, EventListener {
  private static final Logger log = LoggerFactory.getLogger(DefaultDataStreamsMonitoring.class);

  static final long FEATURE_CHECK_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(5);

  private static final StatsPoint REPORT =
      new StatsPoint(DataStreamsTags.EMPTY, 0, 0, 0, 0, 0, 0, 0, null);
  private static final StatsPoint POISON_PILL =
      new StatsPoint(DataStreamsTags.EMPTY, 0, 0, 0, 0, 0, 0, 0, null);

  private final Map<Long, Map<String, StatsBucket>> timeToBucket = new HashMap<>();
  private final MessagePassingQueue<InboxItem> inbox = Queues.mpscArrayQueue(1024);
  private final DatastreamsPayloadWriter payloadWriter;
  private final DDAgentFeaturesDiscovery features;
  private final TimeSource timeSource;
  private final Supplier<TraceConfig> traceConfigSupplier;
  private final long bucketDurationNanos;
  private final Thread thread;
  private final DataStreamsPropagator propagator;
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
            sharedCommunicationObjects.agentHttpClient,
            sharedCommunicationObjects.agentUrl.toString(),
            V01_DATASTREAMS_ENDPOINT,
            false,
            true,
            Collections.emptyMap()),
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
        new MsgPackDatastreamsPayloadWriter(
            sink, config.getWellKnownTags(), DDTraceCoreInfo.VERSION, config.getPrimaryTag()),
        Config.get().getDataStreamsBucketDurationNanoseconds());
  }

  public DefaultDataStreamsMonitoring(
      Sink sink,
      DDAgentFeaturesDiscovery features,
      TimeSource timeSource,
      Supplier<TraceConfig> traceConfigSupplier,
      DatastreamsPayloadWriter payloadWriter,
      long bucketDurationNanos) {
    this.features = features;
    this.timeSource = timeSource;
    this.traceConfigSupplier = traceConfigSupplier;
    this.payloadWriter = payloadWriter;
    this.bucketDurationNanos = bucketDurationNanos;

    thread = newAgentThread(DATA_STREAMS_MONITORING, new InboxProcessor());
    sink.register(this);
    schemaSamplers = new ConcurrentHashMap<>();

    this.propagator = new DataStreamsPropagator(this, this.timeSource, serviceNameOverride);
    DataStreamsTags.setServiceNameOverride(serviceNameOverride);
  }

  @Override
  public void start() {
    checkDynamicConfig();
    cancellation =
        AgentTaskScheduler.get()
            .scheduleAtFixedRate(
                new ReportTask(),
                this,
                bucketDurationNanos,
                bucketDurationNanos,
                TimeUnit.NANOSECONDS);
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
      return new DefaultPathwayContext(timeSource, getThreadServiceName());
    } else {
      return NoopPathwayContext.INSTANCE;
    }
  }

  @Override
  public Propagator propagator() {
    return this.propagator;
  }

  @Override
  public void mergePathwayContextIntoSpan(AgentSpan span, DataStreamsContextCarrier carrier) {
    if (span instanceof DDSpan) {
      DefaultPathwayContext pathwayContext =
          DefaultPathwayContext.extract(
              carrier,
              DataStreamsContextCarrierAdapter.INSTANCE,
              this.timeSource,
              getThreadServiceName());
      ((DDSpan) span).context().mergePathwayContext(pathwayContext);
    }
  }

  public void trackBacklog(DataStreamsTags tags, long value) {
    inbox.offer(new Backlog(tags, value, timeSource.getCurrentTimeNanos(), getThreadServiceName()));
  }

  @Override
  public void reportSchemaRegistryUsage(
      String topic,
      String clusterId,
      int schemaId,
      boolean isSuccess,
      boolean isKey,
      String operation) {
    inbox.offer(
        new SchemaRegistryUsage(
            topic,
            clusterId,
            schemaId,
            isSuccess,
            isKey,
            operation,
            timeSource.getCurrentTimeNanos(),
            getThreadServiceName()));
  }

  @Override
  public void setCheckpoint(AgentSpan span, DataStreamsContext context) {
    PathwayContext pathwayContext = span.context().getPathwayContext();
    if (pathwayContext != null) {
      pathwayContext.setCheckpoint(context, this::add);
    }
  }

  @Override
  public void setConsumeCheckpoint(String type, String source, DataStreamsContextCarrier carrier) {
    setConsumeCheckpoint(type, source, carrier, true);
  }

  public void setConsumeCheckpoint(
      String type, String source, DataStreamsContextCarrier carrier, Boolean isManual) {
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

    DataStreamsTags tags;
    if (isManual) {
      tags = createManual(type, INBOUND, source);
    } else {
      tags = create(type, INBOUND, source);
    }

    setCheckpoint(span, fromTags(tags));
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
    DataStreamsTags tags;
    if (manualCheckpoint) {
      tags = createManual(type, OUTBOUND, target);
    } else {
      tags = create(type, OUTBOUND, target);
    }

    DataStreamsContext dsmContext = fromTags(tags);
    this.propagator.inject(
        span.with(dsmContext), carrier, DataStreamsContextCarrierAdapter.INSTANCE);
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
            } else if (payload instanceof SchemaRegistryUsage) {
              SchemaRegistryUsage usage = (SchemaRegistryUsage) payload;
              StatsBucket statsBucket =
                  getStatsBucket(usage.getTimestampNanos(), usage.getServiceNameOverride());
              statsBucket.addSchemaRegistryUsage(usage);
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
    schemaSamplers.clear();
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
