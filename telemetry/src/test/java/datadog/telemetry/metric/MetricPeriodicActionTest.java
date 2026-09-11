package datadog.telemetry.metric;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Metric;
import datadog.trace.api.telemetry.MetricCollector;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.mockito.ArgumentCaptor;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

public class MetricPeriodicActionTest {

  @TableTest({
    "scenario                                                   | metrics                                                                    | expected                                                          ",
    "single raw metric produces a single telemetry point        | ['col(counter: 2)']                                                        | ['tel(points: [2])']                                              ",
    "two raw metrics with the same identity are joined          | ['col(counter: 2)', 'col(counter: 6)']                                     | ['tel(points: [2, 6])']                                           ",
    "different namespace produces a distinct telemetry metric   | ['col(counter: 2)', 'col(namespace: \"other\", counter: 6)']               | ['tel(points: [2])', 'tel(namespace: \"other\", points: [6])']    ",
    "different common flag produces a distinct telemetry metric | ['col(counter: 2)', 'col(common: false, counter: 6)']                      | ['tel(points: [2])', 'tel(common: false, points: [6])']           ",
    "different metric name produces a distinct telemetry metric | ['col(counter: 2)', 'col(metric: \"other\", counter: 6)']                  | ['tel(points: [2])', 'tel(metric: \"other\", points: [6])']       ",
    "different tags produce a distinct telemetry metric         | ['col(counter: 2)', 'col(tags: [a:b], counter: 6)']                        | ['tel(points: [2])', 'tel(tags: [a:b], points: [6])']             ",
    "distinct tags on both raw metrics stay distinct            | ['col(counter: 2, tags: [a:b])', 'col(counter: 6, tags: [c:d])']           | ['tel(points: [2], tags: [a:b])', 'tel(points: [6], tags: [c:d])']",
    "same tags on both raw metrics are joined                   | ['col(counter: 2, tags: [a:b, c:d])', 'col(counter: 6, tags: [a:b, c:d])'] | ['tel(points: [2, 6], tags: [a:b, c:d])']                         "
  })
  void testCommonMetricsAreJoinedBeforeBeingSentToTelemetry(
      List<MetricCollector.Metric> metrics, List<ExpectedMetric> expected) {
    TelemetryService telemetryService = mock(TelemetryService.class);
    MetricCollector<MetricCollector.Metric> metricCollector = mock(MetricCollector.class);
    when(metricCollector.drain()).thenReturn(metrics);
    when(metricCollector.drainDistributionSeries()).thenReturn(emptyList());
    DefaultMetricPeriodicAction action = new DefaultMetricPeriodicAction(metricCollector);

    action.doIteration(telemetryService);

    ArgumentCaptor<Metric> captor = forClass(Metric.class);
    verify(telemetryService, times(expected.size())).addMetric(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Metric> actualMetrics = captor.getAllValues();
    for (ExpectedMetric expectedMetric : expected) {
      assertMatchingMetric(actualMetrics, expectedMetric);
    }
  }

  @TableTest({
    "scenario                                                   | distributionSeries                                                                 | expected                                                                ",
    "single raw series point produces a single telemetry series | ['rawSeries(value: 2)']                                                            | ['series(points: [2])']                                                 ",
    "two raw series points with the same identity are joined    | ['rawSeries(value: 2)', 'rawSeries(value: 6)']                                     | ['series(points: [2, 6])']                                              ",
    "different namespace produces a distinct telemetry series   | ['rawSeries(value: 2)', 'rawSeries(namespace: \"other\", value: 6)']               | ['series(points: [2])', 'series(namespace: \"other\", points: [6])']    ",
    "different common flag produces a distinct telemetry series | ['rawSeries(value: 2)', 'rawSeries(common: false, value: 6)']                      | ['series(points: [2])', 'series(common: false, points: [6])']           ",
    "different metric name produces a distinct telemetry series | ['rawSeries(value: 2)', 'rawSeries(metric: \"other\", value: 6)']                  | ['series(points: [2])', 'series(metric: \"other\", points: [6])']       ",
    "different tags produce a distinct telemetry series         | ['rawSeries(value: 2)', 'rawSeries(tags: [a:b], value: 6)']                        | ['series(points: [2])', 'series(tags: [a:b], points: [6])']             ",
    "distinct tags on both raw series points stay distinct      | ['rawSeries(value: 2, tags: [a:b])', 'rawSeries(value: 6, tags: [c:d])']           | ['series(points: [2], tags: [a:b])', 'series(points: [6], tags: [c:d])']",
    "same tags on both raw series points are joined             | ['rawSeries(value: 2, tags: [a:b, c:d])', 'rawSeries(value: 6, tags: [a:b, c:d])'] | ['series(points: [2, 6], tags: [a:b, c:d])']                            "
  })
  void testCommonDistributionSeriesAreJoinedBeforeBeingSentToTelemetry(
      List<MetricCollector.DistributionSeriesPoint> distributionSeries,
      List<ExpectedSeries> expected) {
    TelemetryService telemetryService = mock(TelemetryService.class);
    MetricCollector<MetricCollector.Metric> metricCollector = mock(MetricCollector.class);
    when(metricCollector.drain()).thenReturn(emptyList());
    when(metricCollector.drainDistributionSeries()).thenReturn(distributionSeries);
    DefaultMetricPeriodicAction action = new DefaultMetricPeriodicAction(metricCollector);

    action.doIteration(telemetryService);

    ArgumentCaptor<DistributionSeries> captor = forClass(DistributionSeries.class);
    verify(telemetryService, times(expected.size())).addDistributionSeries(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<DistributionSeries> actualSeries = captor.getAllValues();
    for (ExpectedSeries expectedSeries : expected) {
      assertMatchingSeries(actualSeries, expectedSeries);
    }
  }

  /** Parses a {@code col(key: value, ...)} cell into the raw metric it describes. */
  @TypeConverter
  public static MetricCollector.Metric col(String token) {
    Map<String, String> args = parseArgs(token);
    String namespace = stringArg(args, "namespace", "namespace");
    String metricName = stringArg(args, "metric", "metric");
    boolean common = booleanArg(args, "common", true);
    List<String> tags = tagsArg(args);
    long counter = Long.parseLong(args.get("counter"));
    return new MetricCollector.Metric(namespace, common, metricName, "count", counter, tags);
  }

  /** Parses a {@code tel(key: value, ...)} cell into the expected telemetry metric. */
  @TypeConverter
  public static ExpectedMetric tel(String token) {
    Map<String, String> args = parseArgs(token);
    String namespace = stringArg(args, "namespace", "namespace");
    String metricName = stringArg(args, "metric", "metric");
    boolean common = booleanArg(args, "common", true);
    List<String> tags = tagsArg(args);
    List<Long> points = longListArg(args, "points");
    return new ExpectedMetric(namespace, metricName, common, tags, points);
  }

  /** Parses a {@code rawSeries(key: value, ...)} cell into the raw distribution series point. */
  @TypeConverter
  public static MetricCollector.DistributionSeriesPoint rawSeries(String token) {
    Map<String, String> args = parseArgs(token);
    String namespace = stringArg(args, "namespace", "namespace");
    String metricName = stringArg(args, "metric", "metric");
    boolean common = booleanArg(args, "common", true);
    List<String> tags = tagsArg(args);
    int value = Integer.parseInt(args.get("value"));
    return new MetricCollector.DistributionSeriesPoint(metricName, common, namespace, value, tags);
  }

  /** Parses a {@code series(key: value, ...)} cell into the expected telemetry series. */
  @TypeConverter
  public static ExpectedSeries series(String token) {
    Map<String, String> args = parseArgs(token);
    String namespace = stringArg(args, "namespace", "namespace");
    String metricName = stringArg(args, "metric", "metric");
    boolean common = booleanArg(args, "common", true);
    List<String> tags = tagsArg(args);
    List<Integer> points = intListArg(args, "points");
    return new ExpectedSeries(namespace, metricName, common, tags, points);
  }

  /**
   * Splits the {@code (key: value, ...)} argument list of a builder cell such as {@code col(...)}
   * into a name-to-raw-value map, honouring nested {@code [...]} tag/point lists.
   */
  private static Map<String, String> parseArgs(String token) {
    String content = token.substring(token.indexOf('(') + 1, token.lastIndexOf(')')).trim();
    Map<String, String> args = new LinkedHashMap<>();
    if (content.isEmpty()) {
      return args;
    }
    int depth = 0;
    int start = 0;
    for (int i = 0; i <= content.length(); i++) {
      char c = i == content.length() ? ',' : content.charAt(i);
      if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth--;
      } else if (c == ',' && depth == 0) {
        String pair = content.substring(start, i).trim();
        int colon = pair.indexOf(':');
        args.put(pair.substring(0, colon).trim(), pair.substring(colon + 1).trim());
        start = i + 1;
      }
    }
    return args;
  }

  private static String stringArg(Map<String, String> args, String key, String defaultValue) {
    String raw = args.get(key);
    return raw == null ? defaultValue : unquote(raw);
  }

  private static String unquote(String value) {
    return value.charAt(0) == '"' ? value.substring(1, value.length() - 1) : value;
  }

  private static boolean booleanArg(Map<String, String> args, String key, boolean defaultValue) {
    String raw = args.get(key);
    return raw == null ? defaultValue : Boolean.parseBoolean(raw);
  }

  private static List<String> tagsArg(Map<String, String> args) {
    String raw = args.get("tags");
    return raw == null ? emptyList() : splitBracketedList(raw);
  }

  private static List<String> splitBracketedList(String raw) {
    String inner = raw.substring(raw.indexOf('[') + 1, raw.lastIndexOf(']')).trim();
    if (inner.isEmpty()) {
      return emptyList();
    }
    List<String> values = new ArrayList<>();
    for (String part : inner.split(",")) {
      values.add(part.trim());
    }
    return values;
  }

  private static List<Long> longListArg(Map<String, String> args, String key) {
    List<Long> values = new ArrayList<>();
    for (String value : splitBracketedList(args.get(key))) {
      values.add(Long.parseLong(value));
    }
    return values;
  }

  private static List<Integer> intListArg(Map<String, String> args, String key) {
    List<Integer> values = new ArrayList<>();
    for (String value : splitBracketedList(args.get(key))) {
      values.add(Integer.parseInt(value));
    }
    return values;
  }

  private static void assertMatchingMetric(List<Metric> actualMetrics, ExpectedMetric expected) {
    List<Metric> matches =
        actualMetrics.stream()
            .filter(metric -> metric.getNamespace().equals(expected.namespace))
            .filter(metric -> metric.getMetric().equals(expected.metricName))
            .filter(metric -> metric.getCommon().equals(expected.common))
            .filter(metric -> metric.getTags().equals(expected.tags))
            .collect(Collectors.toList());
    assertEquals(
        1,
        matches.size(),
        "expected exactly one match for "
            + expected.namespace
            + "/"
            + expected.metricName
            + " "
            + expected.tags);

    Metric metric = matches.get(0);
    assertEquals(Metric.TypeEnum.COUNT, metric.getType());
    List<Long> actualPoints =
        metric.getPoints().stream()
            .map(point -> point.get(1).longValue())
            .collect(Collectors.toList());
    assertEquals(expected.points.size(), actualPoints.size());
    assertEquals(true, actualPoints.containsAll(expected.points));
  }

  private static void assertMatchingSeries(
      List<DistributionSeries> actualSeries, ExpectedSeries expected) {
    List<DistributionSeries> matches =
        actualSeries.stream()
            .filter(series -> series.getNamespace().equals(expected.namespace))
            .filter(series -> series.getMetric().equals(expected.metricName))
            .filter(series -> series.getCommon().equals(expected.common))
            .filter(series -> series.getTags().equals(expected.tags))
            .collect(Collectors.toList());
    assertEquals(
        1,
        matches.size(),
        "expected exactly one match for "
            + expected.namespace
            + "/"
            + expected.metricName
            + " "
            + expected.tags);

    DistributionSeries series = matches.get(0);
    assertEquals(expected.points.size(), series.getPoints().size());
    assertEquals(true, series.getPoints().containsAll(expected.points));
  }

  private static class ExpectedMetric {
    private final String namespace;
    private final String metricName;
    private final boolean common;
    private final List<String> tags;
    private final List<Long> points;

    ExpectedMetric(
        String namespace, String metricName, boolean common, List<String> tags, List<Long> points) {
      this.namespace = namespace;
      this.metricName = metricName;
      this.common = common;
      this.tags = tags;
      this.points = points;
    }
  }

  private static class ExpectedSeries {
    private final String namespace;
    private final String metricName;
    private final boolean common;
    private final List<String> tags;
    private final List<Integer> points;

    ExpectedSeries(
        String namespace,
        String metricName,
        boolean common,
        List<String> tags,
        List<Integer> points) {
      this.namespace = namespace;
      this.metricName = metricName;
      this.common = common;
      this.tags = tags;
      this.points = points;
    }
  }

  private static class DefaultMetricPeriodicAction extends MetricPeriodicAction {
    private final MetricCollector<MetricCollector.Metric> collector;

    DefaultMetricPeriodicAction(@Nonnull MetricCollector<MetricCollector.Metric> collector) {
      this.collector = collector;
    }

    @Override
    @Nonnull
    public MetricCollector<MetricCollector.Metric> collector() {
      return collector;
    }
  }
}
