package datadog.trace.common.metrics;

import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.PEER_TAGS_CACHE;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.PEER_TAGS_CACHE_ADDER;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.SERVICE_NAMES;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.SPAN_KINDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.metrics.SignalItem.StopSignal;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.util.LRUCache;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.jctools.queues.MessagePassingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Aggregator implements Runnable {

  private static final long DEFAULT_SLEEP_MILLIS = 10;

  private static final Logger log = LoggerFactory.getLogger(Aggregator.class);

  private final MessagePassingQueue<InboxItem> inbox;
  private final LRUCache<MetricKey, AggregateMetric> aggregates;
  private final MetricWriter writer;
  // the reporting interval controls how much history will be buffered
  // when the agent is unresponsive (only 10 pending requests will be
  // buffered by OkHttpSink)
  private final long reportingIntervalNanos;

  private final long sleepMillis;

  /**
   * Per-cycle hook run on the aggregator thread at the start of each report cycle, before the
   * flush. Used by {@link ConflatingMetricsAggregator} to reconcile its cached peer-tag schema
   * against {@link datadog.communication.ddagent.DDAgentFeaturesDiscovery}; running before the
   * flush guarantees that any test awaiting {@code writer.finishBucket()} observes the schema in
   * its post-reconcile state. May be {@code null}.
   */
  private final Runnable onReportCycle;

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
      Runnable onReportCycle) {
    this(
        writer,
        inbox,
        maxAggregates,
        reportingInterval,
        reportingIntervalTimeUnit,
        DEFAULT_SLEEP_MILLIS,
        healthMetrics,
        onReportCycle);
  }

  Aggregator(
      MetricWriter writer,
      MessagePassingQueue<InboxItem> inbox,
      int maxAggregates,
      long reportingInterval,
      TimeUnit reportingIntervalTimeUnit,
      long sleepMillis,
      HealthMetrics healthMetrics,
      Runnable onReportCycle) {
    this.writer = writer;
    this.inbox = inbox;
    this.aggregates =
        new LRUCache<>(
            new AggregateExpiry(healthMetrics), maxAggregates * 4 / 3, 0.75f, maxAggregates);
    this.reportingIntervalNanos = reportingIntervalTimeUnit.toNanos(reportingInterval);
    this.sleepMillis = sleepMillis;
    this.onReportCycle = onReportCycle;
  }

  private static final class AggregateExpiry
      implements LRUCache.ExpiryListener<MetricKey, AggregateMetric> {
    private final HealthMetrics healthMetrics;

    AggregateExpiry(HealthMetrics healthMetrics) {
      this.healthMetrics = healthMetrics;
    }

    @Override
    public void accept(Map.Entry<MetricKey, AggregateMetric> expired) {
      if (expired.getValue().getHitCount() > 0) {
        healthMetrics.onStatsAggregateDropped();
      }
    }
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
      } else if (item instanceof SpanSnapshot && !stopped) {
        SpanSnapshot snapshot = (SpanSnapshot) item;
        MetricKey key = buildMetricKey(snapshot);
        AggregateMetric aggregate = aggregates.computeIfAbsent(key, k -> new AggregateMetric());
        aggregate.recordOneDuration(snapshot.tagAndDuration);
        dirty = true;
      }
    }
  }

  private static MetricKey buildMetricKey(SpanSnapshot s) {
    return new MetricKey(
        s.resourceName,
        SERVICE_NAMES.computeIfAbsent(s.serviceName, UTF8_ENCODE),
        s.operationName,
        s.serviceNameSource,
        s.spanType,
        s.httpStatusCode,
        s.synthetic,
        s.traceRoot,
        SPAN_KINDS.computeIfAbsent(s.spanKind, UTF8BytesString::create),
        materializePeerTags(s.peerTagSchema, s.peerTagValues),
        s.httpMethod,
        s.httpEndpoint,
        s.grpcStatusCode);
  }

  /**
   * Encodes the per-span peer-tag values into the {@code List<UTF8BytesString>} the {@link
   * MetricKey} consumes. Reads name/value pairs at the same index from the schema's names and the
   * snapshot's values; null value slots are skipped (the span didn't set that peer tag).
   */
  private static List<UTF8BytesString> materializePeerTags(PeerTagSchema schema, String[] values) {
    if (schema == null || values == null) {
      return Collections.emptyList();
    }
    String[] names = schema.names;
    int n = names.length;
    // Single-entry fast path (matches the original singletonList shape for INTERNAL spans and any
    // other case where exactly one peer tag fired).
    int firstHit = -1;
    int hitCount = 0;
    for (int i = 0; i < n; i++) {
      if (values[i] != null && hitCount++ == 0) {
        firstHit = i;
      }
    }
    if (hitCount == 0) {
      return Collections.emptyList();
    }
    if (hitCount == 1) {
      return Collections.singletonList(encodePeerTag(names[firstHit], values[firstHit]));
    }
    List<UTF8BytesString> tags = new ArrayList<>(hitCount);
    for (int i = firstHit; i < n; i++) {
      if (values[i] != null) {
        tags.add(encodePeerTag(names[i], values[i]));
      }
    }
    return tags;
  }

  private static UTF8BytesString encodePeerTag(String name, String value) {
    final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
        cacheAndCreator = PEER_TAGS_CACHE.computeIfAbsent(name, PEER_TAGS_CACHE_ADDER);
    return cacheAndCreator.getLeft().computeIfAbsent(value, cacheAndCreator.getRight());
  }

  private void report(long when, SignalItem signal) {
    // Per-cycle hook on the aggregator thread -- used by ClientStatsAggregator to reconcile the
    // cached peer-tag schema against feature discovery. Runs before the flush so any test that
    // awaits writer.finishBucket() observes the schema in its post-reconcile state, and so
    // subsequent producer publishes (which may happen as soon as the flush completes) see the new
    // schema without an additional handoff.
    if (onReportCycle != null) {
      onReportCycle.run();
    }
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
      }
    }
  }

  private long wallClockTime() {
    return MILLISECONDS.toNanos(System.currentTimeMillis());
  }
}
