package com.datadog.debugger.agent;

import com.datadog.debugger.el.ValueScript;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class MetricProbeTest {
  private static final String LANGUAGE = "java";
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";

  @Test
  public void metric() {
    MetricProbe.Builder metricBuilder = createMetric();
    MetricProbe metric =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .build();
    Assert.assertEquals("toString()", metric.getWhere().getMethodName());
    Assert.assertEquals("5-7", metric.getWhere().getLines()[0]);
    Assert.assertEquals(MetricProbe.MetricKind.COUNT, metric.getKind());
    Assert.assertEquals("datadog.debugger.calls", metric.getMetricName());
  }

  @Test
  public void metricWithTags() {
    MetricProbe.Builder metricBuilder = createMetric();
    MetricProbe metric =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .tags("tag1:foo1", "tag2:foo2")
            .build();
    Assert.assertEquals(2, metric.getTags().length);
    Assert.assertEquals("foo1", metric.getTagMap().get("tag1"));
    Assert.assertEquals("foo2", metric.getTagMap().get("tag2"));
  }

  @Test
  public void metricConstantValueScript() {
    MetricProbe.Builder metricBuilder = createMetric();
    MetricProbe metric1 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript(42))
            .build();
    MetricProbe metric2 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript(42))
            .build();
    Assert.assertEquals(metric1, metric2);
  }

  @Test
  public void metricRefValueScript() {
    MetricProbe.Builder metricBuilder = createMetric();
    MetricProbe metric1 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript("^arg"))
            .build();
    MetricProbe metric2 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript("^arg"))
            .build();
    Assert.assertEquals(metric1, metric2);
  }

  private MetricProbe.Builder createMetric() {
    return MetricProbe.builder().language(LANGUAGE).metricId(PROBE_ID);
  }
}
