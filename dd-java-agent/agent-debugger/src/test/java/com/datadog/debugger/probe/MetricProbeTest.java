package com.datadog.debugger.probe;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ValueScript;
import datadog.trace.bootstrap.debugger.ProbeId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MetricProbeTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);

  @Test
  public void metric() {
    MetricProbe.Builder metricBuilder = createMetric();
    MetricProbe metric =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .build();
    Assertions.assertEquals("toString()", metric.getWhere().getMethodName());
    Assertions.assertEquals("5-7", metric.getWhere().getLines()[0]);
    Assertions.assertEquals(MetricProbe.MetricKind.COUNT, metric.getKind());
    Assertions.assertEquals("datadog.debugger.calls", metric.getMetricName());
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
    Assertions.assertEquals(2, metric.getTags().length);
    Assertions.assertEquals("foo1", metric.getTagMap().get("tag1"));
    Assertions.assertEquals("foo2", metric.getTagMap().get("tag2"));
  }

  @Test
  public void metricConstantValueScript() {
    MetricProbe.Builder metricBuilder = createMetric();
    MetricProbe metric1 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript(DSL.value(42), "42"))
            .build();
    MetricProbe metric2 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript(DSL.value(42), "42"))
            .build();
    Assertions.assertEquals(metric1, metric2);
  }

  @Test
  public void metricRefValueScript() {
    MetricProbe.Builder metricBuilder = createMetric();
    MetricProbe metric1 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .build();
    MetricProbe metric2 =
        metricBuilder
            .where("java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"})
            .kind(MetricProbe.MetricKind.COUNT)
            .metricName("datadog.debugger.calls")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .build();
    Assertions.assertEquals(metric1, metric2);
  }

  private MetricProbe.Builder createMetric() {
    return MetricProbe.builder().language(LANGUAGE).probeId(PROBE_ID);
  }
}
