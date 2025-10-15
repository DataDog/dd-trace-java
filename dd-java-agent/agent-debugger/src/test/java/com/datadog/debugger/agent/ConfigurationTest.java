package com.datadog.debugger.agent;

import static com.datadog.debugger.probe.MetricProbe.MetricKind.COUNT;
import static com.datadog.debugger.probe.MetricProbe.MetricKind.GAUGE;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.Sampling;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import utils.TestHelper;

public class ConfigurationTest {

  @Test
  public void getDefinitions() {
    Configuration config1 = createConfig1();
    List<String> definitions =
        config1.getDefinitions().stream().map(d -> d.getId()).sorted().collect(toList());
    List<String> list =
        asList("metric1", "probe1", "log1", "span1", "debug1", "decorateSpan1").stream()
            .sorted()
            .collect(toList());

    assertEquals(list, definitions);
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
    assertEquals("ValueScript{dsl='42'}", metricProbes.get(0).getValue().toString());
    assertEquals("datadog.debugger.gauge_value", metricProbes.get(1).getMetricName());
    assertEquals("ValueScript{dsl='value'}", metricProbes.get(1).getValue().toString());
    assertEquals("datadog.debugger.refpathvalue", metricProbes.get(2).getMetricName());
    assertEquals("ValueScript{dsl='obj.field'}", metricProbes.get(2).getValue().toString());
    assertEquals("datadog.debugger.novalue", metricProbes.get(3).getMetricName());
  }

  @Test
  public void deserializeSpanProbes() throws Exception {
    String content = TestHelper.getFixtureContent("/test_span_probe.json");
    JsonAdapter<Configuration> adapter =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);
    Configuration config = adapter.fromJson(content);
    List<SpanProbe> spanProbes = new ArrayList<>(config.getSpanProbes());
    assertEquals(4, spanProbes.size());
    assertEquals("123356536", spanProbes.get(0).getId());
    assertEquals("123356537", spanProbes.get(1).getId());
    assertEquals("123356538", spanProbes.get(2).getId());
    assertEquals("123356539", spanProbes.get(3).getId());
  }

  @Test
  public void deserializeSpanDecorationProbes() throws Exception {
    String content = TestHelper.getFixtureContent("/test_span_decoration_probe.json");
    JsonAdapter<Configuration> adapter =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);
    Configuration config = adapter.fromJson(content);
    List<SpanDecorationProbe> spanDecorationProbes =
        new ArrayList<>(config.getSpanDecorationProbes());
    assertEquals(4, spanDecorationProbes.size());
    assertEquals(
        SpanDecorationProbe.TargetSpan.ACTIVE, spanDecorationProbes.get(0).getTargetSpan());
    assertEquals(
        "uuid == 'showMe'",
        spanDecorationProbes.get(0).getDecorations().get(0).getWhen().getDslExpression());
    assertEquals(
        "uuid", spanDecorationProbes.get(0).getDecorations().get(0).getTags().get(0).getName());
    assertEquals(
        "uuid={uuid}",
        spanDecorationProbes
            .get(0)
            .getDecorations()
            .get(0)
            .getTags()
            .get(0)
            .getValue()
            .getTemplate());
    assertEquals(
        SpanDecorationProbe.TargetSpan.ACTIVE, spanDecorationProbes.get(1).getTargetSpan());
    assertNull(spanDecorationProbes.get(1).getDecorations().get(0).getWhen());
    assertEquals(
        "uuid", spanDecorationProbes.get(1).getDecorations().get(0).getTags().get(0).getName());
    assertEquals(
        "tag2", spanDecorationProbes.get(1).getDecorations().get(0).getTags().get(1).getName());
    assertEquals(SpanDecorationProbe.TargetSpan.ROOT, spanDecorationProbes.get(2).getTargetSpan());
    assertEquals(SpanDecorationProbe.TargetSpan.ROOT, spanDecorationProbes.get(3).getTargetSpan());
  }

  @Test
  public void deserializeLogProbes() throws Exception {
    String content = TestHelper.getFixtureContent("/test_log_probe.json");
    JsonAdapter<Configuration> adapter =
        MoshiHelper.createMoshiConfig().adapter(Configuration.class);
    Configuration config = adapter.fromJson(content);
    ArrayList<LogProbe> logProbes = new ArrayList<>(config.getLogProbes());
    assertEquals(2, logProbes.size());
    LogProbe logProbe0 = logProbes.get(0);
    assertEquals(2, logProbe0.getTags().length);
    assertEquals("dd_watches_dsl", logProbe0.getTags()[0].getKey());
    assertEquals("{object.objField.intField}", logProbe0.getTags()[0].getValue());
    assertEquals("env", logProbe0.getTags()[1].getKey());
    assertEquals("staging", logProbe0.getTags()[1].getValue());
    assertEquals(8, logProbe0.getSegments().size());
    assertEquals("this is a log line customized! uuid=", logProbe0.getSegments().get(0).getStr());
    assertEquals("uuid", logProbe0.getSegments().get(1).getExpr());
    assertEquals(" result=", logProbe0.getSegments().get(2).getStr());
    assertEquals("result", logProbe0.getSegments().get(3).getExpr());
    assertEquals(" garbageStart=", logProbe0.getSegments().get(4).getStr());
    assertEquals("garbageStart", logProbe0.getSegments().get(5).getExpr());
    assertEquals(" contain=", logProbe0.getSegments().get(6).getStr());
    assertEquals("contains(arg, 'foo')", logProbe0.getSegments().get(7).getExpr());
    LogProbe logProbe1 = logProbes.get(1);
    assertEquals(2, logProbe1.getCaptureExpressions().size());
    LogProbe.CaptureExpression captureExpression0 = logProbe1.getCaptureExpressions().get(0);
    assertEquals("uuid", captureExpression0.getName());
    assertEquals("uuid", captureExpression0.getExpr().getDsl());
    assertEquals(5, captureExpression0.getCapture().getMaxReferenceDepth());
    LogProbe.CaptureExpression captureExpression1 = logProbe1.getCaptureExpressions().get(1);
    assertEquals("field1_map_key_array_3_field2", captureExpression1.getName());
    assertEquals("field1.map['key'].array[3].field2", captureExpression1.getExpr().getDsl());
    assertNull(captureExpression1.getCapture());
  }

  @Test
  public void roundtripSerialization() throws Exception {
    deserialize(serialize());
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
    JsonAdapter<LogProbe.Capture> adapter =
        MoshiHelper.createMoshiConfig().adapter(LogProbe.Capture.class);
    LogProbe.Capture capture = adapter.fromJson(json);
    assertEquals(expectedMaxRef, capture.getMaxReferenceDepth());
    assertEquals(expectedMaxCol, capture.getMaxCollectionSize());
    assertEquals(expectedMaxLen, capture.getMaxLength());
    assertEquals(expectedMaxFieldCount, capture.getMaxFieldCount());
  }

  private String serialize() {
    Configuration config1 = createConfig1();
    Configuration config2 = createConfig2();
    List<Configuration> configs = new ArrayList<>(asList(config1, config2));
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
    assertEquals(10.0, config0.getSampling().getEventsPerSecond(), 0.1);
    // snapshot probe
    assertEquals(2, config0.getLogProbes().size());
    List<LogProbe> logProbes0 = new ArrayList<>(config0.getLogProbes());
    LogProbe snapshotProbe0 = logProbes0.get(0);
    assertEquals("java.lang.String", snapshotProbe0.getWhere().getTypeName());
    assertEquals(MethodLocation.ENTRY, snapshotProbe0.getEvaluateAt());
    assertEquals(2, snapshotProbe0.getTags().length);
    assertTrue(snapshotProbe0.isCaptureSnapshot());
    assertEquals("tag1:value1", snapshotProbe0.getTags()[0].toString());
    assertEquals("tag2:value2", snapshotProbe0.getTags()[1].toString());
    assertEquals(42.0, snapshotProbe0.getSampling().getEventsPerSecond(), 0.1);
    Configuration config1 = configs.get(1);
    assertEquals("service2", config1.getService());
    assertEquals(2, config1.getLogProbes().size());
    List<LogProbe> logProbes1 = new ArrayList<>(config1.getLogProbes());
    LogProbe logProbe1 = logProbes1.get(0);
    assertEquals("java.util.Map", logProbe1.getWhere().getTypeName());
    // metric probe
    assertEquals(1, config0.getMetricProbes().size());
    MetricProbe metricProbe0 = config0.getMetricProbes().iterator().next();
    assertEquals("metric_count", metricProbe0.getMetricName());
    assertEquals(COUNT, metricProbe0.getKind());
    // log probe
    assertEquals(2, config0.getLogProbes().size());
    LogProbe logProbe0 = logProbes0.get(1);
    assertEquals("this is a log line with arg={arg}", logProbe0.getTemplate());
    assertEquals(2, logProbe0.getSegments().size());
    assertEquals("this is a log line with arg=", logProbe0.getSegments().get(0).getStr());
    assertEquals("arg", logProbe0.getSegments().get(1).getExpr());
    assertFalse(logProbe0.isCaptureSnapshot());
    // span probe
    assertEquals(1, config0.getSpanProbes().size());
    SpanProbe spanProbe1 = config0.getSpanProbes().iterator().next();
    SpanProbe spanProbe2 = config1.getSpanProbes().iterator().next();
    assertEquals("12-23", spanProbe2.getWhere().getLines()[0]);
    // span decoration probe
    assertEquals(1, config0.getSpanDecorationProbes().size());
    SpanDecorationProbe spanDecoration1 = config0.getSpanDecorationProbes().iterator().next();
    assertEquals(SpanDecorationProbe.TargetSpan.ACTIVE, spanDecoration1.getTargetSpan());
    assertEquals(
        "arg1 == 'foo'", spanDecoration1.getDecorations().get(0).getWhen().getDslExpression());
    assertEquals("id", spanDecoration1.getDecorations().get(0).getTags().get(0).getName());
    assertEquals(
        "{id}", spanDecoration1.getDecorations().get(0).getTags().get(0).getValue().getTemplate());
  }

  private Configuration createConfig1() {
    LogProbe probe1 = createProbe("probe1", "java.lang.String", "indexOf", "(String)");
    MetricProbe metric1 =
        createMetric("metric1", "metric_count", COUNT, "java.lang.String", "indexOf", "(String)");
    LogProbe log1 =
        createLog(
            "log1", "this is a log line with arg={arg}", "java.lang.String", "indexOf", "(String)");
    SpanProbe span1 = createSpan("span1", "java.lang.String", "indexOf", "(String)");
    TriggerProbe triggerProbe =
        createTriggerProbe("debug1", "java.lang.String", "indexOf", "(String)")
            .setSampling(new Sampling(47, 12.0));

    SpanDecorationProbe.Decoration decoration =
        new SpanDecorationProbe.Decoration(
            new ProbeCondition(
                DSL.when(DSL.eq(DSL.ref("arg1"), DSL.value("foo"))), "arg1 == 'foo'"),
            asList(
                new SpanDecorationProbe.Tag(
                    "id", new SpanDecorationProbe.TagValue("{id}", parseTemplate("{id}")))));
    SpanDecorationProbe spanDecoration1 =
        createDecorationSpan(
            "decorateSpan1",
            SpanDecorationProbe.TargetSpan.ACTIVE,
            decoration,
            "java.lang.String",
            "indexOf",
            "(String)");
    Configuration.FilterList allowList =
        new Configuration.FilterList(asList("java.lang.util"), asList("java.lang.String"));
    Configuration.FilterList denyList =
        new Configuration.FilterList(
            asList("java.security"), asList("javax.security.auth.AuthPermission"));
    LogProbe.Sampling globalSampling = new LogProbe.Sampling(10.0);
    return new Configuration(
        "service1",
        asList(metric1, probe1, log1, span1, triggerProbe, spanDecoration1),
        allowList,
        denyList,
        globalSampling);
  }

  private Configuration createConfig2() {
    LogProbe probe2 = createProbe("probe2", "java.util.Map", "put", null);
    MetricProbe metric2 =
        createMetric("metric2", "metric_gauge", GAUGE, "java.lang.String", "indexOf", "(String)");
    LogProbe log2 =
        createLog(
            "log2",
            "{transactionId}={transactionStatus}, remaining: {{{count(transactions)}}}",
            "java.lang.String",
            "indexOf",
            "(String)");
    SpanProbe span2 = createSpan("span2", "String.java", 12, 23);
    TriggerProbe triggerProbe = createTriggerProbe("debug1", "String.java", "indexOf", "(String)");

    SpanDecorationProbe.Decoration decoration =
        new SpanDecorationProbe.Decoration(
            new ProbeCondition(DSL.when(DSL.eq(DSL.ref("arg"), DSL.value("foo"))), "arg == 'foo'"),
            asList(
                new SpanDecorationProbe.Tag(
                    "tag1", new SpanDecorationProbe.TagValue("{arg1}", parseTemplate("{arg1}"))),
                new SpanDecorationProbe.Tag(
                    "tag2", new SpanDecorationProbe.TagValue("{arg2}", parseTemplate("{arg2}")))));
    SpanDecorationProbe spanDecoration2 =
        createDecorationSpan(
            "span2",
            SpanDecorationProbe.TargetSpan.ACTIVE,
            decoration,
            "String.java",
            "indexOf",
            "(String)");
    Configuration.FilterList allowList =
        new Configuration.FilterList(asList("java.lang.util"), asList("java.lang.String"));
    Configuration.FilterList denyList =
        new Configuration.FilterList(
            asList("java.security"), asList("javax.security.auth.AuthPermission"));
    LogProbe.Sampling globalSampling = new LogProbe.Sampling(10.0);
    return new Configuration(
        "service2",
        asList(metric2, probe2, log2, span2, triggerProbe, spanDecoration2),
        allowList,
        denyList,
        globalSampling);
  }

  private static LogProbe createProbe(
      String id, String typeName, String methodName, String signature) {
    return LogProbe.builder()
        .language("java")
        .probeId(id, 0)
        .captureSnapshot(true)
        .where(typeName, methodName, signature)
        .capture(
            Limits.DEFAULT_REFERENCE_DEPTH,
            Limits.DEFAULT_COLLECTION_SIZE,
            Limits.DEFAULT_LENGTH,
            Limits.DEFAULT_FIELD_COUNT)
        .tags("tag1:value1", "tag2:value2")
        .sampling(42.0)
        .evaluateAt(MethodLocation.ENTRY)
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
        .probeId(id, 0)
        .where(typeName, methodName, signature)
        .evaluateAt(MethodLocation.ENTRY)
        .metricName(metricName)
        .kind(metricKind)
        .tags("tag1:value1", "tag2:value2")
        .build();
  }

  private static LogProbe createLog(
      String id, String template, String typeName, String methodName, String signature) {
    return LogProbe.builder()
        .language("java")
        .probeId(id, 0)
        .captureSnapshot(false)
        .where(typeName, methodName, signature)
        .evaluateAt(MethodLocation.ENTRY)
        .template(template, parseTemplate(template))
        .tags("tag1:value1", "tag2:value2")
        .build();
  }

  private static TriggerProbe createTriggerProbe(
      String id, String typeName, String methodName, String signature) {
    return new TriggerProbe(new ProbeId(id, 0), Where.of(typeName, methodName, signature));
  }

  private static SpanProbe createSpan(
      String id, String typeName, String methodName, String signature) {
    return SpanProbe.builder()
        .language("java")
        .probeId(id, 0)
        .where(typeName, methodName, signature)
        .evaluateAt(MethodLocation.ENTRY)
        .tags("tag1:value1", "tag2:value2")
        .build();
  }

  private static SpanDecorationProbe createDecorationSpan(
      String id,
      SpanDecorationProbe.TargetSpan targetSpan,
      SpanDecorationProbe.Decoration decoration,
      String typeName,
      String methodName,
      String signature) {
    return SpanDecorationProbe.builder()
        .language("java")
        .probeId(id, 0)
        .where(typeName, methodName, signature)
        .evaluateAt(MethodLocation.ENTRY)
        .tags("tag1:value1", "tag2:value2")
        .targetSpan(targetSpan)
        .decorate(decoration)
        .build();
  }

  private static SpanProbe createSpan(String id, String sourceFile, int lineFrom, int lineTill) {
    return SpanProbe.builder()
        .language("java")
        .probeId(id, 0)
        .where(sourceFile, lineFrom, lineTill)
        .tags("tag1:value1", "tag2:value2")
        .build();
  }
}
