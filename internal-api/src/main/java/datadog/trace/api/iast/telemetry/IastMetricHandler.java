package datadog.trace.api.iast.telemetry;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

import datadog.trace.api.iast.telemetry.IastTelemetryCollector.MetricData;
import datadog.trace.api.iast.telemetry.IastTelemetryCollector.Point;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public interface IastMetricHandler {

  boolean add(long value, String tag);

  boolean merge(MetricData metric);

  Collection<MetricData> drain();

  static IastMetricHandler delegating(
      final IastMetric metric, final IastTelemetryCollector collector) {
    return new DelegatingHandler(metric, collector);
  }

  static IastMetricHandler conflated(final IastMetric metric) {
    return metric.getTag() == null
        ? new DefaultHandler(metric, new ConflatedCombiner())
        : new TaggedHandler(metric, ConflatedCombiner::new);
  }

  static IastMetricHandler aggregated(final IastMetric metric) {
    return metric.getTag() == null
        ? new DefaultHandler(metric, new AggregatedCombiner())
        : new TaggedHandler(metric, AggregatedCombiner::new);
  }

  class DelegatingHandler implements IastMetricHandler {

    private final IastMetric metric;
    private final IastTelemetryCollector collector;

    public DelegatingHandler(final IastMetric metric, final IastTelemetryCollector collector) {
      this.metric = metric;
      this.collector = collector;
    }

    @Override
    public boolean add(final long value, final String tag) {
      return collector.addMetric(metric, value, tag);
    }

    @Override
    public boolean merge(final MetricData metric) {
      // delegating collector handles merge separately
      return true;
    }

    @Override
    public Collection<MetricData> drain() {
      return emptyList(); // delegating collector does not drain metrics
    }
  }

  class DefaultHandler implements IastMetricHandler {

    private final IastMetric metric;
    private final Combiner combiner;

    public DefaultHandler(final IastMetric metric, final Combiner combiner) {
      this.metric = metric;
      this.combiner = combiner;
    }

    @Override
    public boolean add(final long value, final String tag) {
      return combiner.add(value);
    }

    @Override
    public boolean merge(final MetricData metric) {
      return combiner.merge(metric);
    }

    @Override
    public Collection<MetricData> drain() {
      final List<Point> points = combiner.drain();
      return points.isEmpty() ? emptyList() : singletonList(new MetricData(metric, points));
    }
  }

  class TaggedHandler implements IastMetricHandler {

    private static final String EMPTY_TAG = "";

    private final IastMetric metric;
    private final Supplier<Combiner> supplier;
    private final ConcurrentHashMap<String, Combiner> map;

    public TaggedHandler(final IastMetric metric, final Supplier<Combiner> supplier) {
      this.metric = metric;
      this.supplier = supplier;
      map = new ConcurrentHashMap<>(); // map is bounded by the tags allowed for the metric
    }

    @Override
    public boolean add(final long value, final String tag) {
      return getOrCreateCombiner(tag).add(value);
    }

    @Override
    public boolean merge(final MetricData metric) {
      return getOrCreateCombiner(metric.getTag()).merge(metric);
    }

    private Combiner getOrCreateCombiner(final String tag) {
      final String key = tag == null ? EMPTY_TAG : tag;
      Combiner combiner = map.get(key);
      if (combiner == null) {
        combiner = supplier.get();
        final Combiner old = map.putIfAbsent(key, combiner);
        combiner = old == null ? combiner : old;
      }
      return combiner;
    }

    @Override
    public Collection<MetricData> drain() {
      if (map.isEmpty()) {
        return emptyList();
      }
      final List<MetricData> values = new LinkedList<>();
      for (final Map.Entry<String, Combiner> entry : map.entrySet()) {
        final List<Point> points = entry.getValue().drain();
        if (!points.isEmpty()) {
          values.add(new MetricData(metric, entry.getKey(), points));
        }
      }
      map.clear();
      return values;
    }
  }

  interface Combiner {
    boolean add(final long value);

    boolean merge(MetricData metric);

    List<Point> drain();
  }

  class ConflatedCombiner implements Combiner {
    private final AtomicLong value = new AtomicLong(0);

    @Override
    public boolean add(final long value) {
      this.value.addAndGet(value);
      return true;
    }

    @Override
    public boolean merge(final MetricData metric) {
      final long total = metric.getPoints().stream().mapToLong(Point::getValue).sum();
      this.value.addAndGet(total);
      return true;
    }

    @Override
    public List<Point> drain() {
      final long prev = value.getAndSet(0);
      return prev == 0 ? emptyList() : singletonList(new Point(prev));
    }
  }

  class AggregatedCombiner implements Combiner {

    private static final int DEFAULT_MAX_SIZE = 100;
    private final Queue<Point> value;

    public AggregatedCombiner() {
      this(DEFAULT_MAX_SIZE);
    }

    public AggregatedCombiner(final int maxSize) {
      this.value = new ArrayBlockingQueue<>(maxSize);
    }

    @Override
    public boolean add(final long value) {
      return this.value.offer(new Point(value));
    }

    @Override
    public boolean merge(final MetricData metric) {
      boolean result = true;
      for (final Point point : metric.getPoints()) {
        result &= value.offer(point);
      }
      return result;
    }

    @Override
    public List<Point> drain() {
      if (this.value.isEmpty()) {
        return emptyList();
      }
      final List<Point> points = new ArrayList<>(this.value);
      this.value.clear();
      return points;
    }
  }
}
