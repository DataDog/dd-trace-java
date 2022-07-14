package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.MetricProbe.MetricKind.COUNT;
import static com.datadog.debugger.agent.MetricProbe.MetricKind.GAUGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.FieldExtractor;
import datadog.trace.bootstrap.debugger.ValueConverter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ConfigurationTest {

  @Test
  public void roundtripSerialization() throws Exception {
    String buffer = serialize();
    System.out.println(buffer);
    deserialize(buffer);
  }

  private String serialize() throws IOException {
    SnapshotProbe probe1 =
        createProbe("probe1", "service1", "java.lang.String", "indexOf", "(String)");
    SnapshotProbe probe2 = createProbe("probe2", "service2", "java.util.Map", "put", null);
    MetricProbe metric1 =
        createMetric("metric1", "metric_count", COUNT, "java.lang.String", "indexOf", "(String)");
    MetricProbe metric2 =
        createMetric("metric2", "metric_gauge", GAUGE, "java.lang.String", "indexOf", "(String)");
    Configuration.FilterList allowList =
        new Configuration.FilterList(
            Arrays.asList("java.lang.util"), Arrays.asList("java.lang.String"));
    Configuration.FilterList denyList =
        new Configuration.FilterList(
            Arrays.asList("java.security"), Arrays.asList("javax.security.auth.AuthPermission"));
    SnapshotProbe.Sampling globalSampling = new SnapshotProbe.Sampling(10.0);
    Configuration.OpsConfiguration opsConfiguration = new Configuration.OpsConfiguration(10);
    Configuration config1 =
        new Configuration(
            "service1",
            2,
            Arrays.asList(probe1),
            Arrays.asList(metric1),
            allowList,
            denyList,
            globalSampling,
            opsConfiguration);
    Configuration config2 =
        new Configuration(
            "service2",
            2,
            Arrays.asList(probe2),
            Arrays.asList(metric2),
            allowList,
            denyList,
            globalSampling,
            opsConfiguration);
    List<Configuration> configs = new ArrayList<>(Arrays.asList(config1, config2));
    ParameterizedType type = Types.newParameterizedType(List.class, Configuration.class);
    JsonAdapter<List<Configuration>> adapter = MoshiHelper.createMoshiConfig().adapter(type);
    return adapter.toJson(configs);
  }

  private void deserialize(String buffer) throws IOException {
    ParameterizedType type = Types.newParameterizedType(List.class, Configuration.class);
    JsonAdapter<List<Configuration>> adapter = MoshiHelper.createMoshiConfig().adapter(type);
    List<Configuration> configs = adapter.fromJson(buffer);
    assertEquals(2, configs.size());
    Configuration config0 = configs.get(0);
    assertEquals("service1", config0.getId());
    assertEquals(1, config0.getSnapshotProbes().size());
    SnapshotProbe snapshotProbe1 = config0.getSnapshotProbes().iterator().next();
    assertEquals("java.lang.String", snapshotProbe1.getWhere().getTypeName());
    assertEquals(1, snapshotProbe1.getAllProbeIds().count());
    assertEquals(2, snapshotProbe1.getTags().length);
    assertEquals("tag1:value1", snapshotProbe1.getTags()[0].toString());
    assertEquals("tag2:value2", snapshotProbe1.getTags()[1].toString());
    Configuration config1 = configs.get(1);
    assertEquals("service2", config1.getId());
    assertEquals(1, config1.getSnapshotProbes().size());
    SnapshotProbe snapshotProbe2 = config1.getSnapshotProbes().iterator().next();
    assertEquals("java.util.Map", snapshotProbe2.getWhere().getTypeName());
    assertEquals(1, config0.getMetricProbes().size());
    MetricProbe metricProbe1 = config0.getMetricProbes().iterator().next();
    assertEquals("metric_count", metricProbe1.getMetricName());
    assertEquals(COUNT, metricProbe1.getKind());
    assertEquals(0, metricProbe1.getAdditionalProbes().size());
    assertEquals(1, metricProbe1.getAllProbeIds().count());
  }

  private static SnapshotProbe createProbe(
      String id, String appId, String typeName, String methodName, String signature)
      throws IOException {
    return SnapshotProbe.builder()
        .language("java")
        .probeId(id)
        .active(true)
        .where(typeName, methodName, signature)
        .capture(
            ValueConverter.DEFAULT_REFERENCE_DEPTH,
            ValueConverter.DEFAULT_COLLECTION_SIZE,
            ValueConverter.DEFAULT_LENGTH,
            FieldExtractor.DEFAULT_FIELD_DEPTH,
            FieldExtractor.DEFAULT_FIELD_COUNT)
        .tags("tag1:value1", "tag2:value2")
        .build();
  }

  private static MetricProbe createMetric(
      String id,
      String metricName,
      MetricProbe.MetricKind metricKind,
      String typeName,
      String methodName,
      String signature) {
    return MetricProbe.builder()
        .language("java")
        .metricId(id)
        .active(true)
        .where(typeName, methodName, signature)
        .metricName(metricName)
        .kind(metricKind)
        .tags("tag1:value1", "tag2:value2")
        .build();
  }
}
