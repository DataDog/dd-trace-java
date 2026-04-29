package datadog.trace.common.metrics;

import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static java.util.Collections.unmodifiableSet;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.metrics.SignalItem.StopSignal;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.util.LRUCache;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Aggregator implements Runnable {

  private static final long DEFAULT_SLEEP_MILLIS = 10;

  private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

  static final Set<String> ELIGIBLE_SPAN_KINDS_FOR_PEER_AGGREGATION =
      unmodifiableSet(
          new HashSet<>(Arrays.asList(SPAN_KIND_CLIENT, SPAN_KIND_PRODUCER, SPAN_KIND_CONSUMER)));

  private static final DDCache<String, UTF8BytesString> SERVICE_NAMES =
      DDCaches.newFixedSizeCache(32);

  private static final DDCache<CharSequence, UTF8BytesString> SPAN_KINDS =
      DDCaches.newFixedSizeCache(16);
  private static final DDCache<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      PEER_TAGS_CACHE = DDCaches.newFixedSizeCache(64);
  private static final Function<
          String, Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>>
      PEER_TAGS_CACHE_ADDER =
          key ->
              Pair.of(
                  DDCaches.newFixedSizeCache(512),
                  value -> UTF8BytesString.create(key + ":" + value));

  private final MessagePassingQueue<InboxItem> inbox;
  private final LRUCache<MetricKey, AggregateMetric> aggregates;
  // Downgraded from ConcurrentHashMap: only accessed on the aggregator thread
  private final HashMap<MetricKey, MetricKey> keys;
  private final MetricWriter writer;
  private final HealthMetrics healthMetrics;
  // the reporting interval controls how much history will be buffered
  // when the agent is unresponsive (only 10 pending requests will be
  // buffered by OkHttpSink)
  private final long reportingIntervalNanos;

  private final long sleepMillis;

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "the field is confined to the agent thread running the Aggregator")
  private boolean dirty;

  Aggregator(
      MetricWriter writer,
      MessagePassingQueue<InboxItem> inbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      HealthMetrics healthMetrics,
      DDAgentFeaturesDiscovery features,
      boolean includeEndpointInMetrics) {
    this(
        writer,
        inbox,
        maxAggregates,
        reportingInterval,
        reportingIntervalTimeUnit,
        DEFAULT_SLEEP_MILLIS,
        healthMetrics,
        features,
        includeEndpointInMetrics);
  }

  Aggregator(
      MetricWriter writer,
      MessagePassingQueue<InboxItem> inbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      long sleepMillis,
      HealthMetrics healthMetrics,
      DDAgentFeaturesDiscovery features,
      boolean includeEndpointInMetrics) {
    this.writer = writer;
    this.inbox = inbox;
    this.keys = new HashMap<>();
    this.aggregates =
        new LRUCache<>(new CommonKeyCleaner(keys), maxAggregates * 4 / 3, 0.75f, maxAggregates);
    this.reportingIntervalNanos = reportingIntervalTimeUnit.toNanos(reportingInterval);
    this.sleepMillis = sleepMillis;
    this.healthMetrics = healthMetrics;
  }

  public void clearAggregates() {
    this.aggregates.clear();
  }

  @Override
  public void run() {
    Thread currentThread = Thread.currentThread();
    Drainer drainer = new Drainer();
    while (!currentThread.isInterrupted() && !drainer.stopped) {
      try {
        if (!inbox.isEmpty()) {
          inbox.drain(drainer);
        } else {
          Thread.sleep(sleepMillis);
        }
      } catch (InterruptedException e) {
        currentThread.interrupt();
      } catch (Throwable error) {
        log.debug("error aggregating metrics", error);
      }
    }
    log.debug("metrics aggregator exited");
  }

  private final class Drainer implements MessagePassingQueue.Consumer<InboxItem> {

    boolean stopped = false;

    @Override
    public void accept(InboxItem item) {
      if (item instanceof SignalItem) {
        SignalItem signal = (SignalItem) item;
        if (!stopped) {
          report(wallClockTime(), signal);
          stopped = item instanceof StopSignal;
          if (stopped) {
            signal.complete();
          }
        } else {
          signal.ignore();
        }
      } else if (item instanceof TraceStatsData && !stopped) {
        processTraceStats((TraceStatsData) item);
      }
    }
  }

  /** Process all span stats from a trace on the background thread. */
  private void processTraceStats(TraceStatsData traceData) {
    int counted = traceData.spans.length;
    for (SpanStatsData spanData : traceData.spans) {
      publishSpan(spanData);
    }
    healthMetrics.onClientStatTraceComputed(counted, traceData.totalSpanCount, !traceData.hasError);
  }

  /**
   * Construct MetricKey from SpanStatsData and aggregate -- all on the background thread. This is
   * the expensive work that was previously done on the foreground span-finish thread.
   */
  private void publishSpan(SpanStatsData span) {
    List<UTF8BytesString> peerTags = buildPeerTags(span.peerTagValues);

    MetricKey newKey =
        new MetricKey(
            span.resourceName,
            SERVICE_NAMES.computeIfAbsent(span.serviceName, UTF8_ENCODE),
            span.operationName,
            span.serviceNameSource,
            span.spanType,
            span.httpStatusCode,
            span.synthetic,
            span.traceRoot,
            SPAN_KINDS.computeIfAbsent(span.spanKind, UTF8BytesString::create),
            peerTags,
            span.httpMethod,
            span.httpEndpoint,
            span.grpcStatusCode);
    MetricKey key = keys.putIfAbsent(newKey, newKey);
    if (null == key) {
      key = newKey;
    }
    long tag =
        (span.error > 0 ? AggregateMetric.ERROR_TAG : 0L)
            | (span.topLevel ? AggregateMetric.TOP_LEVEL_TAG : 0L);
    long durationNanos = span.durationNano;

    AggregateMetric aggregate = aggregates.computeIfAbsent(key, k -> new AggregateMetric());
    aggregate.recordDuration(tag | durationNanos);
    dirty = true;
  }

  /** Build UTF8BytesString peer tags from the flat [name, value, name, value, ...] array. */
  private static List<UTF8BytesString> buildPeerTags(Object[] peerTagValues) {
    if (peerTagValues == null || peerTagValues.length == 0) {
      return Collections.emptyList();
    }
    List<UTF8BytesString> peerTags = new ArrayList<>(peerTagValues.length / 2);
    for (int i = 0; i < peerTagValues.length; i += 2) {
      String tagName = (String) peerTagValues[i];
      String tagValue = (String) peerTagValues[i + 1];
      final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
          cacheAndCreator = PEER_TAGS_CACHE.computeIfAbsent(tagName, PEER_TAGS_CACHE_ADDER);
      peerTags.add(cacheAndCreator.getLeft().computeIfAbsent(tagValue, cacheAndCreator.getRight()));
    }
    return peerTags;
  }

  private void report(long when, SignalItem signal) {
    boolean skipped = true;
    if (dirty) {
      try {
        expungeStaleAggregates();
        if (!aggregates.isEmpty()) {
          skipped = false;
          writer.startBucket(aggregates.size(), when, reportingIntervalNanos);
          for (Map.Entry<MetricKey, AggregateMetric> aggregate : aggregates.entrySet()) {
            writer.add(aggregate.getKey(), aggregate.getValue());
            aggregate.getValue().clear();
          }
          // note that this may do IO and block
          writer.finishBucket();
        }
      } catch (Throwable error) {
        writer.reset();
        log.debug("Error publishing metrics. Dropping payload", error);
      }
      dirty = false;
    }
    signal.complete();
    if (skipped) {
      log.debug("skipped metrics reporting because no points have changed");
    }
  }

  private void expungeStaleAggregates() {
    Iterator<Map.Entry<MetricKey, AggregateMetric>> it = aggregates.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<MetricKey, AggregateMetric> pair = it.next();
      AggregateMetric metric = pair.getValue();
      if (metric.getHitCount() == 0) {
        it.remove();
        keys.remove(pair.getKey());
      }
    }
  }

  private long wallClockTime() {
    return MILLISECONDS.toNanos(System.currentTimeMillis());
  }

  private static final class CommonKeyCleaner
      implements LRUCache.ExpiryListener<MetricKey, AggregateMetric> {

    private final Map<MetricKey, MetricKey> keys;

    private CommonKeyCleaner(Map<MetricKey, MetricKey> keys) {
      this.keys = keys;
    }

    @Override
    public void accept(Map.Entry<MetricKey, AggregateMetric> expired) {
      keys.remove(expired.getKey());
    }
  }
}
