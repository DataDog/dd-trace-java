package com.datadog.debugger.agent;

import static com.datadog.debugger.probe.MetricProbe.MetricKind.COUNT;
import static com.datadog.debugger.probe.MetricProbe.MetricKind.GAUGE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SnapshotProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.Limits;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import utils.TestHelper;

public class ConfigurationTest {

  @Test
  public void getDefinitions() {
    Configuration config1 = createConfig1();
    assertEquals(4, config1.getDefinitions().size());
    Iterator<ProbeDefinition> iterator = config1.getDefinitions().iterator();
    assertEquals("probe1", iterator.next().getId());
    assertEquals("metric1", iterator.next().getId());
    assertEquals("log1", iterator.next().getId());
    assertEquals("span1", iterator.next().getId());
  }

  @Test
  public void deserializeMetricProbes() throws Exception {
    String content = TestHelper.getFixtureContent("/test_metric_probe.json");
    JsonAdapter<Configuration> adapter =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);
    Configuration config = adapter.fromJson(content);
    ArrayList<MetricProbe> metricProbes = new ArrayList<>(config.getMetricProbes());
    assertEquals(4, metricProbes.size());
    assertEquals("datadog.debugger.calls", metricProbes.get(0).getMetricName());
    assertEquals(
        "ValueScript{expr=NumericLiteral{value=42}, dsl='42'}",
        metricProbes.get(0).getValue().toString());
    assertEquals("datadog.debugger.gauge_value", metricProbes.get(1).getMetricName());
    assertEquals(
        "ValueScript{expr=ValueRefExpression{symbolName='value'}, dsl='value'}",
        metricProbes.get(1).getValue().toString());
    assertEquals("datadog.debugger.refpathvalue", metricProbes.get(2).getMetricName());
    assertEquals(
        "ValueScript{expr=GetMemberExpression{target=ValueRefExpression{symbolName='obj'}, memberName='field'}, dsl='obj.field'}",
        metricProbes.get(2).getValue().toString());
    assertEquals("datadog.debugger.novalue", metricProbes.get(3).getMetricName());
  }

  @Test
  public void deserializeLogProbes() throws Exception {
    String content = TestHelper.getFixtureContent("/test_log_probe.json");
    JsonAdapter<Configuration> adapter =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);
    Configuration config = adapter.fromJson(content);
    ArrayList<LogProbe> logProbes = new ArrayList<>(config.getLogProbes());
    assertEquals(1, logProbes.size());
    LogProbe logProbe0 = logProbes.get(0);
    assertEquals(6, logProbe0.getSegments().size());
    assertEquals("this is a log line customized! uuid=", logProbe0.getSegments().get(0).getStr());
    assertEquals("uuid", logProbe0.getSegments().get(1).getExpr());
    assertEquals(" result=", logProbe0.getSegments().get(2).getStr());
    assertEquals("result", logProbe0.getSegments().get(3).getExpr());
    assertEquals(" garbageStart=", logProbe0.getSegments().get(4).getStr());
    assertEquals("garbageStart", logProbe0.getSegments().get(5).getExpr());
  }

  @Test
  public void roundtripSerialization() throws Exception {
    String buffer = serialize();
    System.out.println(buffer);
    deserialize(buffer);
  }

  @Test
  public void captureDeserialization() throws IOException {
    doCaptureDeserialization(
        "{\"maxReferenceDepth\":3,\"maxCollectionSize\":123,\"maxLength\":242,\"maxFieldCount\":2}",
        3,
        123,
        242,
        2);
    doCaptureDeserialization(
        "{\"maxReferenceDepth\":3}",
        3,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        Limits.DEFAULT_FIELD_COUNT);
    doCaptureDeserialization(
        "{\"maxCollectionSize\":123}",
        Limits.DEFAULT_REFERENCE_DEPTH,
        123,
        Limits.DEFAULT_LENGTH,
        Limits.DEFAULT_FIELD_COUNT);
    doCaptureDeserialization(
        "{\"maxLength\":242}",
        Limits.DEFAULT_REFERENCE_DEPTH,
        Limits.DEFAULT_COLLECTION_SIZE,
        242,
        Limits.DEFAULT_FIELD_COUNT);
    doCaptureDeserialization(
        "{\"maxFieldDepth\":7}",
        Limits.DEFAULT_REFERENCE_DEPTH,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        Limits.DEFAULT_FIELD_COUNT);
    doCaptureDeserialization(
        "{\"maxFieldCount\":2}",
        Limits.DEFAULT_REFERENCE_DEPTH,
        Limits.DEFAULT_COLLECTION_SIZE,
        Limits.DEFAULT_LENGTH,
        2);
  }

  private void doCaptureDeserialization(
      String json,
      int expectedMaxRef,
      int expectedMaxCol,
      int expectedMaxLen,
      int expectedMaxFieldCount)
      throws IOException {
    JsonAdapter<SnapshotProbe.Capture> adapter =
        MoshiHelper.createMoshiConfig().adapter(SnapshotProbe.Capture.class);
    SnapshotProbe.Capture capture = adapter.fromJson(json);
    assertEquals(expectedMaxRef, capture.getMaxReferenceDepth());
    assertEquals(expectedMaxCol, capture.getMaxCollectionSize());
    assertEquals(expectedMaxLen, capture.getMaxLength());
    assertEquals(expectedMaxFieldCount, capture.getMaxFieldCount());
  }

  private String serialize() {
    Configuration config1 = createConfig1();
    Configuration config2 = createConfig2();
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
    assertEquals("service1", config0.getService());
    assertEquals(10.0, config0.getSampling().getSnapshotsPerSecond(), 0.1);
    // snapshot probe
    assertEquals(1, config0.getSnapshotProbes().size());
    SnapshotProbe snapshotProbe1 = config0.getSnapshotProbes().iterator().next();
    assertEquals("java.lang.String", snapshotProbe1.getWhere().getTypeName());
    assertEquals(ProbeDefinition.MethodLocation.ENTRY, snapshotProbe1.getEvaluateAt());
    assertEquals(1, snapshotProbe1.getAllProbeIds().count());
    assertEquals(2, snapshotProbe1.getTags().length);
    assertEquals("tag1:value1", snapshotProbe1.getTags()[0].toString());
    assertEquals("tag2:value2", snapshotProbe1.getTags()[1].toString());
    assertEquals(42.0, snapshotProbe1.getSampling().getSnapshotsPerSecond(), 0.1);
    Configuration config1 = configs.get(1);
    assertEquals("service2", config1.getService());
    assertEquals(1, config1.getSnapshotProbes().size());
    SnapshotProbe snapshotProbe2 = config1.getSnapshotProbes().iterator().next();
    assertEquals("java.util.Map", snapshotProbe2.getWhere().getTypeName());
    // metric probe
    assertEquals(1, config0.getMetricProbes().size());
    MetricProbe metricProbe1 = config0.getMetricProbes().iterator().next();
    assertEquals("metric_count", metricProbe1.getMetricName());
    assertEquals(COUNT, metricProbe1.getKind());
    assertEquals(0, metricProbe1.getAdditionalProbes().size());
    assertEquals(1, metricProbe1.getAllProbeIds().count());
    // log probe
    assertEquals(1, config0.getLogProbes().size());
    LogProbe logProbe1 = config0.getLogProbes().iterator().next();
    assertEquals("this is a log line with arg={arg}", logProbe1.getTemplate());
    assertEquals(2, logProbe1.getSegments().size());
    assertEquals("this is a log line with arg=", logProbe1.getSegments().get(0).getStr());
    assertEquals("arg", logProbe1.getSegments().get(1).getExpr());
    // span probe
    assertEquals(1, config0.getSpanProbes().size());
    SpanProbe spanProbe1 = config0.getSpanProbes().iterator().next();
    assertEquals("span", spanProbe1.getName());
  }

  private Configuration createConfig1() {
    SnapshotProbe probe1 = createProbe("probe1", "java.lang.String", "indexOf", "(String)");
    MetricProbe metric1 =
        createMetric("metric1", "metric_count", COUNT, "java.lang.String", "indexOf", "(String)");
    LogProbe log1 =
        createLog(
            "log1", "this is a log line with arg={arg}", "java.lang.String", "indexOf", "(String)");
    SpanProbe span1 = createSpan("span1", "java.lang.String", "indexOf", "(String)", "span");
    Configuration.FilterList allowList =
        new Configuration.FilterList(
            Arrays.asList("java.lang.util"), Arrays.asList("java.lang.String"));
    Configuration.FilterList denyList =
        new Configuration.FilterList(
            Arrays.asList("java.security"), Arrays.asList("javax.security.auth.AuthPermission"));
    SnapshotProbe.Sampling globalSampling = new SnapshotProbe.Sampling(10.0);
    return new Configuration(
        "service1",
        Arrays.asList(probe1),
        Arrays.asList(metric1),
        Arrays.asList(log1),
        Arrays.asList(span1),
        allowList,
        denyList,
        globalSampling);
  }

  private Configuration createConfig2() {
    SnapshotProbe probe2 = createProbe("probe2", "java.util.Map", "put", null);
    MetricProbe metric2 =
        createMetric("metric2", "metric_gauge", GAUGE, "java.lang.String", "indexOf", "(String)");
    LogProbe log2 =
        createLog(
            "log2",
            "{transactionId}={transactionStatus}, remaining: {{{count(transactions)}}}",
            "java.lang.String",
            "indexOf",
            "(String)");
    SpanProbe span2 = createSpan("span2", "java.lang.String", "indexOf", "(String)", "span");
    Configuration.FilterList allowList =
        new Configuration.FilterList(
            Arrays.asList("java.lang.util"), Arrays.asList("java.lang.String"));
    Configuration.FilterList denyList =
        new Configuration.FilterList(
            Arrays.asList("java.security"), Arrays.asList("javax.security.auth.AuthPermission"));
    SnapshotProbe.Sampling globalSampling = new SnapshotProbe.Sampling(10.0);
    return new Configuration(
        "service2",
        Arrays.asList(probe2),
        Arrays.asList(metric2),
        Arrays.asList(log2),
        Arrays.asList(span2),
        allowList,
        denyList,
        globalSampling);
  }

  private static SnapshotProbe createProbe(
      String id, String typeName, String methodName, String signature) {
    return SnapshotProbe.builder()
        .language("java")
        .probeId(id)
        .active(true)
        .where(typeName, methodName, signature)
        .capture(
            Limits.DEFAULT_REFERENCE_DEPTH,
            Limits.DEFAULT_COLLECTION_SIZE,
            Limits.DEFAULT_LENGTH,
            Limits.DEFAULT_FIELD_COUNT)
        .tags("tag1:value1", "tag2:value2")
        .sampling(42.0)
        .evaluateAt(ProbeDefinition.MethodLocation.ENTRY)
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
        .probeId(id)
        .active(true)
        .where(typeName, methodName, signature)
        .evaluateAt(ProbeDefinition.MethodLocation.ENTRY)
        .metricName(metricName)
        .kind(metricKind)
        .tags("tag1:value1", "tag2:value2")
        .build();
  }

  private static LogProbe createLog(
      String id, String template, String typeName, String methodName, String signature) {
    return LogProbe.builder()
        .language("java")
        .probeId(id)
        .active(true)
        .where(typeName, methodName, signature)
        .evaluateAt(ProbeDefinition.MethodLocation.ENTRY)
        .template(template)
        .tags("tag1:value1", "tag2:value2")
        .build();
  }

  private static SpanProbe createSpan(
      String id, String typeName, String methodName, String signature, String spanName) {
    return SpanProbe.builder()
        .language("java")
        .probeId(id)
        .active(true)
        .where(typeName, methodName, signature)
        .evaluateAt(ProbeDefinition.MethodLocation.ENTRY)
        .tags("tag1:value1", "tag2:value2")
        .name(spanName)
        .build();
  }
}
