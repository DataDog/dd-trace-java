package datadog.trace.api.telemetry;

import datadog.trace.util.HashingUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static datadog.trace.api.telemetry.MetricCollector.Metric;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public interface MetricCollector<M extends Metric> {

  int RAW_QUEUE_SIZE = 1024;

  // TODO All implementations are based on queueing metrics and never care when the queue is full.
  // TODO This leads to always resetting counters even if the related metrics could not be enqueued.
  // TODO So we may loose metric information during this call.
  void prepareMetrics();

  Collection<M> drain();

  default Collection<DistributionSeriesPoint> drainDistributionSeries() {
    return Collections.emptySet();
  }

  class Metric {
    public final String metricName;
    public final boolean common;
    public final String namespace;
    public final String type;
    public final long timestamp;
    public final Number value;
    public final List<String> tags;

    public Metric(
        String namespace,
        boolean common,
        String metricName,
        String type,
        Number value,
        final String tag) {
      this(
          namespace,
          common,
          metricName,
          type,
          value,
          tag == null ? emptyList() : singletonList(tag));
    }

    public Metric(
        String namespace,
        boolean common,
        String metricName,
        String type,
        Number value,
        final String... tags) {
      this(namespace, common, metricName, type, value, Arrays.asList(tags));
    }

    public Metric(
        String namespace,
        boolean common,
        String metricName,
        String type,
        Number value,
        final List<String> tags) {
      this.namespace = namespace;
      this.common = common;
      this.metricName = metricName;
      this.type = type;
      this.timestamp = System.currentTimeMillis() / 1000;
      this.value = value;
      this.tags = tags;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) return true;
      if (!(o instanceof Metric)) return false;
      Metric metric = (Metric) o;
      return common == metric.common
          && Objects.equals(metricName, metric.metricName)
          && Objects.equals(namespace, metric.namespace)
          && Objects.equals(tags, metric.tags);
    }

    @Override
    public int hashCode() {
      return HashingUtils.hash(metricName, common, namespace, tags);
    }

    @Override
    public String toString() {
      return "Metric{"
          + "metricName='"
          + metricName
          + '\''
          + ", common="
          + common
          + ", namespace='"
          + namespace
          + '\''
          + ", type='"
          + type
          + '\''
          + ", timestamp="
          + timestamp
          + ", value="
          + value
          + ", tags="
          + tags
          + '}';
    }
  }

  class DistributionSeriesPoint {
    public final String metricName;
    public final boolean common;
    public final String namespace;
    public final int value;
    public final List<String> tags;

    public DistributionSeriesPoint(
        String metricName, boolean common, String namespace, int value, List<String> tags) {
      this.metricName = metricName;
      this.common = common;
      this.namespace = namespace;
      this.value = value;
      this.tags = tags;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      DistributionSeriesPoint that = (DistributionSeriesPoint) o;
      return common == that.common
          && Objects.equals(metricName, that.metricName)
          && Objects.equals(namespace, that.namespace)
          && Objects.equals(tags, that.tags);
    }

    @Override
    public int hashCode() {
      return HashingUtils.hash(metricName, common, namespace, tags);
    }

    @Override
    public String toString() {
      return "DistributionSeriesPoint{"
          + "metricName='"
          + metricName
          + '\''
          + ", common="
          + common
          + ", namespace='"
          + namespace
          + '\''
          + ", value="
          + value
          + ", tags="
          + tags
          + '}';
    }
  }
}
