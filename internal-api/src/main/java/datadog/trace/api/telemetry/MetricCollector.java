package datadog.trace.api.telemetry;

import static datadog.trace.api.telemetry.MetricCollector.Metric;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public interface MetricCollector<M extends Metric> {

  int RAW_QUEUE_SIZE = 1024;

  void prepareMetrics();

  Collection<M> drain();

  class Metric {
    public final String metricName;
    public final boolean common;
    public final long timestamp;
    public final long counter;
    public final String namespace;
    public final List<String> tags;

    public Metric(
        String namespace, boolean common, String metricName, long counter, final String tag) {
      this(namespace, common, metricName, counter, tag == null ? emptyList() : singletonList(tag));
    }

    public Metric(
        String namespace, boolean common, String metricName, long counter, final String... tags) {
      this(namespace, common, metricName, counter, Arrays.asList(tags));
    }

    public Metric(
        String namespace,
        boolean common,
        String metricName,
        long counter,
        final List<String> tags) {
      this.common = common;
      this.metricName = metricName;
      this.timestamp = System.currentTimeMillis() / 1000;
      this.counter = counter;
      this.namespace = namespace;
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
      return Objects.hash(metricName, common, namespace, tags);
    }
  }
}
