package datadog.trace.api.telemetry;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.tabletest.junit.TableTest;

class MetricCollectorTest {

  @TableTest({
    "scenario              | firstTags      | secondCommon | secondNamespace | secondMetric | secondTags     | expected",
    "same metric           |                |              |                 |              |                | true    ",
    "different common      |                | false        |                 |              |                | false   ",
    "different namespace   |                |              | other           |              |                | false   ",
    "different metric name |                |              |                 | other        |                | false   ",
    "extra tag             |                |              |                 |              | ['a:b']        | false   ",
    "same tag              | ['a:b']        |              |                 |              | ['a:b']        | true    ",
    "different tag         | ['a:b']        |              |                 |              | ['c:d']        | false   ",
    "same multiple tags    | ['a:b', 'c:d'] |              |                 |              | ['a:b', 'c:d'] | true    "
  })
  void testMetricEquality(
      List<String> firstTags,
      Boolean secondCommon,
      String secondNamespace,
      String secondMetric,
      List<String> secondTags,
      boolean expected) {
    MetricCollector.Metric first = counter(null, null, null, firstTags, 2);
    MetricCollector.Metric second =
        counter(secondNamespace, secondCommon, secondMetric, secondTags, 6);

    // when
    boolean sameMetric = first.equals(second);

    // then
    assertEquals(expected, sameMetric);
  }

  @TableTest({
    "scenario        | namespace | tags           | value",
    "default         |           |                | 2    ",
    "other namespace | other     |                | 6    ",
    "multiple tags   |           | ['a:b', 'c:d'] | 2    "
  })
  void testMetricToString(String namespace, List<String> tags, int value) {
    MetricCollector.Metric metric = counter(namespace, null, null, tags, value);

    // expect
    assertNotNull(metric.toString());
  }

  private static MetricCollector.Metric counter(
      String namespace, Boolean common, String metric, List<String> tags, Number value) {
    List<String> metricTags = tags == null ? emptyList() : tags;
    return new MetricCollector.Metric(
        namespace == null ? "namespace" : namespace,
        common == null || common,
        metric == null ? "metric" : metric,
        "count",
        value,
        metricTags.toArray(new String[0]));
  }
}
