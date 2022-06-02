package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.MetricProbe.MetricKind.COUNT;
import static com.datadog.debugger.agent.MetricProbe.MetricKind.GAUGE;
import static com.datadog.debugger.agent.MetricProbe.MetricKind.HISTOGRAM;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.InsnListValue;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class ProbeMetricsOnDemandTest {
  private static final String LANGUAGE = "java";
  private static final String METRIC_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String METRIC_ID1 = "beae1807-f3b0-4ea8-a74f-826790c5e6f6";
  private static final String METRIC_ID2 = "beae1807-f3b0-4ea8-a74f-826790c5e6f7";
  private static final long ORG_ID = 2;
  private static final String SERVICE_NAME = "service-name";

  private Instrumentation instr = ByteBuddyAgent.install();
  private ClassFileTransformer currentTransformer;
  private MockSink mockSink;

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
  }

  @Test
  public void methodIncCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installSingleMetric(METRIC_NAME, COUNT, CLASS_NAME, "main", "int (java.lang.String)", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(1, listener.counters.get(METRIC_NAME).longValue());
    Assert.assertArrayEquals(null, listener.lastTags);
  }

  @Test
  public void methodIncCountWithTagsMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            null,
            new String[] {"tag1:foo1", "tag2:foo2"});
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(1, listener.counters.get(METRIC_NAME).longValue());
    Assert.assertArrayEquals(new String[] {"tag1:foo1", "tag2:foo2"}, listener.lastTags);
  }

  @Test
  public void methodConstantValueMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    final String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "main", "int (java.lang.String)", new ValueScript(42L));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(42, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void invalidConstantValueMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            new ValueScript(42.0));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Invalid type value definition: 42.0, expect text or integral type.",
        mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void invalidValueMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            new ValueScript("value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Invalid value definition: value", mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void methodArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "f1", "int (int)", new ValueScript("^value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(48, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(31, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void methodArgumentRefValueGaugeMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_gauge";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, GAUGE, CLASS_NAME, "f1", "int (int)", new ValueScript("^value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(48, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(31, listener.counters.get(METRIC_NAME).longValue());
    Assert.assertArrayEquals(null, listener.lastTags);
  }

  @Test
  public void methodArgumentRefValueGaugeMetricWithTags() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_gauge";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME,
            GAUGE,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript("^value"),
            new String[] {"tag1:foo1"});
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertArrayEquals(new String[] {"tag1:foo1"}, listener.lastTags);
  }

  @Test
  public void methodArgumentRefValueHistogramMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_gauge";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, HISTOGRAM, CLASS_NAME, "f1", "int (int)", new ValueScript("^value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(48, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(31, listener.counters.get(METRIC_NAME).longValue());
    Assert.assertArrayEquals(null, listener.lastTags);
  }

  @Test
  public void methodArgumentRefValueHistogramMetricWithTags()
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_gauge";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME,
            HISTOGRAM,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript("^value"),
            new String[] {"tag1:foo1"});
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(48, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(31, listener.counters.get(METRIC_NAME).longValue());
    Assert.assertArrayEquals(new String[] {"tag1:foo1"}, listener.lastTags);
  }

  @Test
  public void lineArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    MetricProbe metricProbe =
        createMetric(METRIC_ID, METRIC_NAME, COUNT, CLASS_NAME, new ValueScript("^value"), "4");
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(48, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(31, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void invalidNameArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "f1", "int (int)", new ValueScript("^foo"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(48, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Cannot resolve argument foo", mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void invalidTypeArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            new ValueScript("^arg"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(48, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Incompatible type for argument arg: java.lang.String with expected type: long",
        mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void lineLocalRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "localvar_count";
    MetricProbe metricProbe =
        createMetric(METRIC_ID, METRIC_NAME, COUNT, CLASS_NAME, new ValueScript("#var1"), "9");
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(3, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void invalidNameLocalRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "localvar_count";
    MetricProbe metricProbe =
        createMetric(METRIC_ID, METRIC_NAME, COUNT, CLASS_NAME, new ValueScript("#foo"), "9");
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Cannot resolve local var foo", mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void invalidTypeLocalRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "localvar_count";
    MetricProbe metricProbe =
        createMetric(METRIC_ID, METRIC_NAME, COUNT, CLASS_NAME, new ValueScript("#arg"), "9");
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Incompatible type for local var arg: java.lang.String with expected type: long",
        mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void methodFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "f", "()", new ValueScript(".intValue"));

    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assert.assertEquals(42, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(24, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void lineFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricProbe metricProbe =
        createMetric(METRIC_ID, METRIC_NAME, COUNT, CLASS_NAME, new ValueScript(".intValue"), "24");
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assert.assertEquals(42, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(48, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void lineMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    MetricProbe metricProbe1 =
        createMetric(
            METRIC_ID1, METRIC_NAME1, COUNT, CLASS_NAME, new ValueScript("#sdata.intValue"), "24");
    MetricProbe metricProbe2 =
        createMetric(
            METRIC_ID2,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript("#cdata.s1.intValue"),
            "24");
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME1));
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME2));
    Assert.assertEquals(42, listener.counters.get(METRIC_NAME1).longValue());
    Assert.assertEquals(101, listener.counters.get(METRIC_NAME2).longValue());
  }

  @Test
  public void nullMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    MetricProbe metricProbe1 =
        createMetric(
            METRIC_ID1,
            METRIC_NAME1,
            COUNT,
            CLASS_NAME,
            new ValueScript("#nullObject.intValue"),
            "25");
    MetricProbe metricProbe2 =
        createMetric(
            METRIC_ID2,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript("#cdata.nullsd.intValue"),
            "25");
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME1));
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME2));
    Assert.assertTrue(mockSink.getCurrentDiagnostics().isEmpty());
  }

  @Test
  public void invalidNameMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    MetricProbe metricProbe1 =
        createMetric(
            METRIC_ID1, METRIC_NAME1, COUNT, CLASS_NAME, new ValueScript("#sdata.foovalue"), "24");
    MetricProbe metricProbe2 =
        createMetric(
            METRIC_ID2,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript("#cdata.s1.foovalue"),
            "24");
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME1));
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME2));
    Assert.assertEquals(
        "Cannot resolve field foovalue", mockSink.getCurrentDiagnostics().get(0).getMessage());
    Assert.assertEquals(
        "Cannot resolve field foovalue", mockSink.getCurrentDiagnostics().get(1).getMessage());
  }

  @Test
  public void invalidTypeMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    MetricProbe metricProbe1 =
        createMetric(
            METRIC_ID1, METRIC_NAME1, COUNT, CLASS_NAME, new ValueScript("#sdata.strValue"), "24");
    MetricProbe metricProbe2 =
        createMetric(
            METRIC_ID2,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript("#cdata.s1.strValue"),
            "24");
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    Assert.assertEquals(143, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME1));
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME2));
    Assert.assertEquals(
        "Incompatible type for field strValue: java.lang.String with expected type: long",
        mockSink.getCurrentDiagnostics().get(0).getMessage());
    Assert.assertEquals(
        "Incompatible type for field strValue: java.lang.String with expected type: long",
        mockSink.getCurrentDiagnostics().get(1).getMessage());
  }

  @Test
  public void invalidNameFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "f", "()", new ValueScript(".fooValue"));

    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assert.assertEquals(42, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Cannot resolve field fooValue", mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void invalidTypeFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "f", "()", new ValueScript(".strValue"));

    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    Assert.assertEquals(42, result);
    Assert.assertFalse(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(
        "Incompatible type for field strValue: java.lang.String with expected type: long",
        mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  @Test
  public void singleLineIncCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "main", "int (java.lang.String)", null, null, "8");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(1, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void multiLineIncCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installSingleMetric(
            METRIC_NAME, COUNT, CLASS_NAME, "main", "int (java.lang.String)", null, null, "4-8");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    Assert.assertEquals(3, result);
    Assert.assertTrue(listener.counters.containsKey(METRIC_NAME));
    Assert.assertEquals(2, listener.counters.get(METRIC_NAME).longValue());
    result = Reflect.on(testClass).call("main", "2").get();
    Assert.assertEquals(3, listener.counters.get(METRIC_NAME).longValue());
  }

  private MetricForwarderListener installSingleMetric(
      String metricName,
      MetricProbe.MetricKind metricKind,
      String typeName,
      String methodName,
      String signature,
      ValueScript valueScript) {
    MetricProbe metricProbe =
        createMetric(
            METRIC_ID, metricName, metricKind, typeName, methodName, signature, valueScript, null);
    return installMetricProbes(metricProbe);
  }

  private MetricForwarderListener installSingleMetric(
      String metricName,
      MetricProbe.MetricKind metricKind,
      String typeName,
      String methodName,
      String signature,
      ValueScript valueScript,
      String[] tags,
      String... lines) {
    MetricProbe metricProbe =
        createMetric(
            METRIC_ID,
            metricName,
            metricKind,
            typeName,
            methodName,
            signature,
            valueScript,
            tags,
            lines);
    return installMetricProbes(metricProbe);
  }

  private static MetricProbe createMetric(
      String id,
      String metricName,
      MetricProbe.MetricKind metricKind,
      String typeName,
      String methodName,
      String signature,
      ValueScript valueScript,
      String[] tags,
      String... lines) {
    return createMetricBuilder(id, metricName, metricKind)
        .where(typeName, methodName, signature, lines)
        .kind(COUNT)
        .valueScript(valueScript)
        .tags(tags)
        .build();
  }

  private static MetricProbe createMetric(
      String id,
      String metricName,
      MetricProbe.MetricKind metricKind,
      String sourceFile,
      ValueScript valueScript,
      String... lines) {
    return createMetricBuilder(id, metricName, metricKind)
        .where(sourceFile, lines)
        .kind(COUNT)
        .valueScript(valueScript)
        .build();
  }

  private static MetricProbe.Builder createMetricBuilder(
      String id, String metricName, MetricProbe.MetricKind metricKind) {
    return MetricProbe.builder()
        .language(LANGUAGE)
        .metricId(id)
        .metricName(metricName)
        .kind(metricKind);
  }

  private MetricForwarderListener installMetricProbes(Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    currentTransformer = new DebuggerTransformer(config, configuration);
    instr.addTransformer(currentTransformer);
    MetricForwarderListener listener = new MetricForwarderListener();
    mockSink = new MockSink();
    DebuggerContext.init(mockSink, null, listener);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    return listener;
  }

  private MetricForwarderListener installMetricProbes(MetricProbe... metricProbes) {
    return installMetricProbes(
        new Configuration(SERVICE_NAME, ORG_ID, null, Arrays.asList(metricProbes)));
  }

  @Test
  public void instListValueNull() {
    InsnListValue insnListValue = new InsnListValue(null);
    Assert.assertTrue(insnListValue.isNull());
  }

  private static class MetricForwarderListener implements DebuggerContext.MetricForwarder {
    Map<String, Long> counters = new HashMap<>();
    String[] lastTags = null;

    @Override
    public void count(String name, long delta, String[] tags) {
      counters.compute(name, (key, value) -> value != null ? value + delta : delta);
      lastTags = tags;
    }

    @Override
    public void gauge(String name, long value, String[] tags) {
      lastTags = tags;
    }

    @Override
    public void histogram(String name, long value, String[] tags) {
      lastTags = tags;
    }
  }

  private static class MockSink implements DebuggerContext.Sink {

    private final List<DiagnosticMessage> currentDiagnostics = new ArrayList<>();

    @Override
    public void addSnapshot(Snapshot snapshot) {}

    @Override
    public void addDiagnostics(String probeId, List<DiagnosticMessage> messages) {
      for (DiagnosticMessage msg : messages) {
        System.out.println(msg);
      }
      currentDiagnostics.addAll(messages);
    }

    public List<DiagnosticMessage> getCurrentDiagnostics() {
      return currentDiagnostics;
    }
  }
}
