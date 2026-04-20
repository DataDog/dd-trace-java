package com.datadog.debugger.agent;

import static com.datadog.debugger.probe.MetricProbe.MetricKind.COUNT;
import static com.datadog.debugger.probe.MetricProbe.MetricKind.DISTRIBUTION;
import static com.datadog.debugger.probe.MetricProbe.MetricKind.GAUGE;
import static com.datadog.debugger.probe.MetricProbe.MetricKind.HISTOGRAM;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;
import static utils.InstrumentationTestHelper.getLineForLineProbe;
import static utils.InstrumentationTestHelper.loadClass;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.joor.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class MetricProbesInstrumentationTest {
  private static final String LANGUAGE = "java";
  private static final ProbeId METRIC_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId METRIC_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId METRIC_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);
  private static final ProbeId METRIC_ID3 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f9", 0);
  private static final ProbeId LINE_METRIC_ID1 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000001", 0);
  private static final ProbeId LINE_METRIC_ID2 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000002", 0);
  private static final ProbeId LINE_METRIC_ID3 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000003", 0);
  private static final ProbeId LINE_METRIC_ID4 =
      new ProbeId("beae1817-f3b0-4ea8-a74f-000000000004", 0);
  private static final String SERVICE_NAME = "service-name";
  private static final String METRIC_PROBEID_TAG =
      "debugger.probeid:beae1807-f3b0-4ea8-a74f-826790c5e6f8";

  private final List<DiagnosticMessage> currentDiagnostics = new ArrayList<>();
  private Instrumentation instr = ByteBuddyAgent.install();
  private ClassFileTransformer currentTransformer;
  private ProbeStatusSink probeStatusSink;

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
        installMethodMetric(METRIC_NAME, COUNT, CLASS_NAME, "main", "int (java.lang.String)", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(1, listener.counters.get(METRIC_NAME).longValue());
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodIncCountWithTagsMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            null,
            new String[] {"tag1:foo1", "tag2:foo2"});
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(1, listener.counters.get(METRIC_NAME).longValue());
    assertArrayEquals(
        new String[] {"tag1:foo1", "tag2:foo2", METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodConstantValueMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    final String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            new ValueScript(DSL.value(42L), "42"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(42, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void invalidConstantValueMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            new ValueScript(DSL.value(42.0), "42.0"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink)
        .addError(eq(METRIC_ID), eq("Unsupported constant value: 42.0 type: java.lang.Double"));
  }

  @Test
  public void invalidValueMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            new ValueScript(DSL.ref("value"), "value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink).addError(eq(METRIC_ID), eq("Cannot resolve symbol value"));
  }

  @Test
  public void multiInvalidValueMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricProbe metric1 =
        MetricProbe.builder()
            .probeId(METRIC_ID1)
            .metricName(METRIC_NAME)
            .kind(COUNT)
            .where(CLASS_NAME, "main", "int (java.lang.String)")
            .valueScript(new ValueScript(DSL.ref("value"), "value"))
            .build();
    MetricProbe metric2 =
        MetricProbe.builder()
            .probeId(METRIC_ID2)
            .metricName(METRIC_NAME)
            .kind(COUNT)
            .where(CLASS_NAME, "main", "int (java.lang.String)")
            .valueScript(new ValueScript(DSL.ref("invalid"), "invalid"))
            .build();
    MetricForwarderListener listener = installMetricProbes(metric1, metric2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, listener.counters.size());
    ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
    verify(probeStatusSink, times(2)).addError(any(), msgCaptor.capture());
    assertEquals("Cannot resolve symbol value", msgCaptor.getAllValues().get(0));
    assertEquals("Cannot resolve symbol invalid", msgCaptor.getAllValues().get(1));
  }

  @Test
  public void methodArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript(DSL.ref("value"), "value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(31, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void methodArgumentRefValueGaugeMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_gauge";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            GAUGE,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript(DSL.ref("value"), "value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertTrue(listener.gauges.containsKey(METRIC_NAME));
    assertEquals(31, listener.gauges.get(METRIC_NAME).longValue());
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodFieldRefValueGaugeDoubleMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_double_gauge";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            GAUGE,
            CLASS_NAME,
            "f",
            "()",
            new ValueScript(DSL.ref("doubleValue"), "doubleValue"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.doubleGauges.containsKey(METRIC_NAME));
    assertEquals(3.14, listener.doubleGauges.get(METRIC_NAME).doubleValue(), 0.001);
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodArgumentRefValueGaugeMetricWithTags() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_gauge";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            GAUGE,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript(DSL.ref("value"), "value"),
            new String[] {"tag1:foo1"});
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertArrayEquals(new String[] {"tag1:foo1", METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodArgumentRefValueHistogramMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_histogram";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            HISTOGRAM,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript(DSL.ref("value"), "value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertTrue(listener.histograms.containsKey(METRIC_NAME));
    assertEquals(31, listener.histograms.get(METRIC_NAME).longValue());
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodFieldRefValueHistogramDoubleMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_double_histogram";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            HISTOGRAM,
            CLASS_NAME,
            "f",
            "()",
            new ValueScript(DSL.ref("doubleValue"), "doubleValue"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.doubleHistograms.containsKey(METRIC_NAME));
    assertEquals(3.14, listener.doubleHistograms.get(METRIC_NAME).doubleValue(), 0.001);
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodArgumentRefValueHistogramMetricWithTags()
      throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_histogram";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            HISTOGRAM,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript(DSL.ref("value"), "value"),
            new String[] {"tag1:foo1"});
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertTrue(listener.histograms.containsKey(METRIC_NAME));
    assertEquals(31, listener.histograms.get(METRIC_NAME).longValue());
    assertArrayEquals(new String[] {"tag1:foo1", METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodArgumentRefValueDistributionMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_distribution";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            DISTRIBUTION,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript(DSL.ref("value"), "value"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertTrue(listener.distributions.containsKey(METRIC_NAME));
    assertEquals(31, listener.distributions.get(METRIC_NAME).longValue());
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodFieldRefValueDistributionDoubleMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_double_distribution";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            DISTRIBUTION,
            CLASS_NAME,
            "f",
            "()",
            new ValueScript(DSL.ref("doubleValue"), "doubleValue"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.doubleDistributions.containsKey(METRIC_NAME));
    assertEquals(3.14, listener.doubleDistributions.get(METRIC_NAME).doubleValue(), 0.001);
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodSyntheticReturnGaugeMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "syn_gauge";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.ref("@return"), "@return"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.gauges.containsKey(METRIC_NAME));
    assertEquals(42, listener.gauges.get(METRIC_NAME).longValue());
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodSyntheticReturnLenGaugeMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot32";
    String METRIC_NAME = "syn_gauge";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, GAUGE)
            .where(CLASS_NAME, "processArg", "(String)")
            .valueScript(new ValueScript(DSL.len(DSL.ref("@return")), "len(@return)"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "foobar").get();
    assertEquals(42, result);
    assertTrue(listener.gauges.containsKey(METRIC_NAME));
    assertEquals(6, listener.gauges.get(METRIC_NAME).longValue());
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodSyntheticReturnInvalidType() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    final String INHERITED_CLASS_NAME = CLASS_NAME + "$Inherited";
    String METRIC_NAME = "syn_gauge";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, GAUGE)
            .where(INHERITED_CLASS_NAME, "<init>", "()")
            .valueScript(new ValueScript(DSL.ref("@return"), "@return"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    assertEquals(42, result);
    assertFalse(listener.gauges.containsKey(METRIC_NAME));
    verify(probeStatusSink).addError(eq(METRIC_ID), eq("Cannot resolve symbol @return"));
  }

  @Test
  public void methodSyntheticDurationGaugeMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "syn_gauge";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.ref("@duration"), "@duration"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.doubleGauges.containsKey(METRIC_NAME));
    assertTrue(listener.doubleGauges.get(METRIC_NAME).doubleValue() > 0);
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void methodSyntheticDurationExceptionGaugeMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot05";
    String METRIC_NAME = "syn_gauge";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, GAUGE)
            .where(CLASS_NAME, "main", "(String)")
            .valueScript(new ValueScript(DSL.ref("@duration"), "@duration"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    try {
      Reflect.on(testClass).call("main", "triggerUncaughtException").get();
      fail("should not reach this code");
    } catch (Exception ex) {
      assertEquals("oops", ex.getCause().getCause().getMessage());
    }
    assertTrue(listener.doubleGauges.containsKey(METRIC_NAME));
    assertTrue(listener.doubleGauges.get(METRIC_NAME).doubleValue() > 0);
    assertArrayEquals(new String[] {METRIC_PROBEID_TAG}, listener.lastTags);
  }

  @Test
  public void lineArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    int line = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID1);
    MetricProbe metricProbe =
        createLineMetric(
            LINE_METRIC_ID1,
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.ref("value"), "value"),
            line);
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(31, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void invalidNameArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "f1",
            "int (int)",
            new ValueScript(DSL.ref("foo"), "foo"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink).addError(eq(METRIC_ID), eq("Cannot resolve symbol foo"));
  }

  @Test
  public void invalidTypeArgumentRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot03";
    String METRIC_NAME = "argument_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "main",
            "int (java.lang.String)",
            new ValueScript(DSL.ref("arg"), "arg"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(48, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink)
        .addError(
            eq(METRIC_ID),
            eq("Incompatible type for expression: java.lang.String with expected types: [long]"));
  }

  @Test
  public void lineLocalRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "localvar_count";
    int line = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID2);
    MetricProbe metricProbe =
        createLineMetric(
            LINE_METRIC_ID2,
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.ref("var1"), "var1"),
            line);
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(3, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void invalidNameLocalRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "localvar_count";
    int line = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID2);
    MetricProbe metricProbe =
        createLineMetric(
            LINE_METRIC_ID2,
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.ref("foo"), "foo"),
            line);
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink).addError(eq(LINE_METRIC_ID2), eq("Cannot resolve symbol foo"));
  }

  @Test
  public void invalidTypeLocalRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "localvar_count";
    int line = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID2);
    MetricProbe metricProbe =
        createLineMetric(
            LINE_METRIC_ID2,
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.ref("arg"), "arg"),
            line);
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink)
        .addError(
            eq(LINE_METRIC_ID2),
            eq("Incompatible type for expression: java.lang.String with expected types: [long]"));
  }

  @Test
  public void methodFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "f",
            "()",
            new ValueScript(DSL.ref("intValue"), "intValue"));

    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(24, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void methodThisFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "f",
            "()",
            new ValueScript(DSL.getMember(DSL.ref("this"), "intValue"), "this.intValue"));

    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(24, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void lineFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    int line = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID1);
    MetricProbe metricProbe =
        createLineMetric(
            LINE_METRIC_ID1,
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.getMember(DSL.ref("this"), "intValue"), "intValue"),
            line);
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(48, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void lineMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    int line3 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID3);
    int line4 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID4);
    MetricProbe metricProbe1 =
        createLineMetric(
            LINE_METRIC_ID3,
            METRIC_NAME1,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.getMember(DSL.ref("sdata"), "intValue"), "sdata.intValue"),
            line3);
    MetricProbe metricProbe2 =
        createLineMetric(
            LINE_METRIC_ID4,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript(
                DSL.getMember(DSL.getMember(DSL.ref("cdata"), "s1"), "intValue"),
                "cdata.s1.intValue"),
            line4);
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    assertEquals(143, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME1));
    assertTrue(listener.counters.containsKey(METRIC_NAME2));
    assertEquals(42, listener.counters.get(METRIC_NAME1).longValue());
    assertEquals(101, listener.counters.get(METRIC_NAME2).longValue());
  }

  @Test
  public void nullMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    int line1 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID1);
    int line2 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID2);
    MetricProbe metricProbe1 =
        createLineMetric(
            LINE_METRIC_ID1,
            METRIC_NAME1,
            COUNT,
            CLASS_NAME,
            new ValueScript(
                DSL.getMember(DSL.ref("nullObject"), "intValue"), "nullObject.intValue"),
            line1);
    MetricProbe metricProbe2 =
        createLineMetric(
            LINE_METRIC_ID2,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript(
                DSL.getMember(DSL.getMember(DSL.ref("cdata"), "nullsd"), "intValue"),
                "cdata.nullsd.intValue"),
            line2);
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    assertEquals(143, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME1));
    assertFalse(listener.counters.containsKey(METRIC_NAME2));
    verify(probeStatusSink, times(0)).addError(any(), anyString());
  }

  @Test
  public void invalidNameMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    int line3 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID3);
    int line4 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID4);
    MetricProbe metricProbe1 =
        createLineMetric(
            LINE_METRIC_ID3,
            METRIC_NAME1,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.getMember(DSL.ref("sdata"), "foovalue"), "sdata.foovalue"),
            line3);
    MetricProbe metricProbe2 =
        createLineMetric(
            LINE_METRIC_ID4,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript(
                DSL.getMember(DSL.getMember(DSL.ref("cdata"), "s1"), "foovalue"),
                "cdata.s1.foovalue"),
            line4);
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    assertEquals(143, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME1));
    assertFalse(listener.counters.containsKey(METRIC_NAME2));
    ArgumentCaptor<String> strCaptor = ArgumentCaptor.forClass(String.class);
    verify(probeStatusSink, times(2)).addError(any(), strCaptor.capture());
    assertEquals("Cannot resolve field foovalue", strCaptor.getAllValues().get(0));
    assertEquals("Cannot resolve field foovalue", strCaptor.getAllValues().get(1));
  }

  @Test
  public void invalidTypeMultiFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    String METRIC_NAME1 = "field1_count";
    String METRIC_NAME2 = "field2_count";
    int line3 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID3);
    int line4 = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID4);
    MetricProbe metricProbe1 =
        createLineMetric(
            LINE_METRIC_ID3,
            METRIC_NAME1,
            COUNT,
            CLASS_NAME,
            new ValueScript(DSL.getMember(DSL.ref("sdata"), "strValue"), "sdata.strValue"),
            line3);
    MetricProbe metricProbe2 =
        createLineMetric(
            LINE_METRIC_ID4,
            METRIC_NAME2,
            COUNT,
            CLASS_NAME,
            new ValueScript(
                DSL.getMember(DSL.getMember(DSL.ref("cdata"), "s1"), "strValue"),
                "cdata.s1.strValue"),
            line4);
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "").get();
    assertEquals(143, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME1));
    assertFalse(listener.counters.containsKey(METRIC_NAME2));
    ArgumentCaptor<String> strCaptor = ArgumentCaptor.forClass(String.class);
    verify(probeStatusSink, times(2)).addError(any(), strCaptor.capture());
    assertEquals(
        "Incompatible type for expression: java.lang.String with expected types: [long]",
        strCaptor.getAllValues().get(0));
    assertEquals(
        "Incompatible type for expression: java.lang.String with expected types: [long]",
        strCaptor.getAllValues().get(1));
  }

  @Test
  public void invalidNameFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "f",
            "()",
            new ValueScript(DSL.ref("fooValue"), "fooValue"));

    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink).addError(eq(METRIC_ID), eq("Cannot resolve symbol fooValue"));
  }

  @Test
  public void invalidTypeFieldRefValueCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricForwarderListener listener =
        installMethodMetric(
            METRIC_NAME,
            COUNT,
            CLASS_NAME,
            "f",
            "()",
            new ValueScript(DSL.ref("strValue"), "strValue"));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertFalse(listener.counters.containsKey(METRIC_NAME));
    verify(probeStatusSink)
        .addError(
            eq(METRIC_ID),
            eq("Incompatible type for expression: java.lang.String with expected types: [long]"));
  }

  @Test
  public void metricThrows() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    ValueScript valueScript =
        new ValueScript(DSL.getMember(DSL.ref("this"), "intValue"), "intValue");
    MetricProbe countProbe =
        createMethodMetric(
            METRIC_ID1, "field_count", COUNT, CLASS_NAME, "f", "()", valueScript, null);
    MetricProbe gaugeProbe =
        createMethodMetric(
            METRIC_ID2, "field_gauge", GAUGE, CLASS_NAME, "f", "()", valueScript, null);
    MetricProbe histogramProbe =
        createMethodMetric(
            METRIC_ID3, "field_histo", HISTOGRAM, CLASS_NAME, "f", "()", valueScript, null);
    MetricForwarderListener listener = installMetricProbes(countProbe, gaugeProbe, histogramProbe);
    listener.setThrowing(true);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
  }

  @Test
  public void singleLineIncCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    int line = getLineForLineProbe(CLASS_NAME, LINE_METRIC_ID1);
    MetricForwarderListener listener =
        installMetricProbes(
            createLineMetric(LINE_METRIC_ID1, METRIC_NAME, COUNT, CLASS_NAME, null, line));
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(1, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  @Disabled("no more support of line range")
  public void multiLineIncCountMetric() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    String METRIC_NAME = "call_count";
    MetricForwarderListener listener =
        installMetricProbes(
            createMetricBuilder(METRIC_ID, METRIC_NAME, COUNT)
                .where("main", "int (java.lang.String)", null, null, "4-8")
                .build());
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(2, listener.counters.get(METRIC_NAME).longValue());
    result = Reflect.on(testClass).call("main", "2").get();
    assertEquals(3, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void evaluateAtEntry() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, COUNT)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.ref("intValue"), "intValue"))
            .evaluateAt(MethodLocation.ENTRY)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(24, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void evaluateAtExit() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String METRIC_NAME = "field_count";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, COUNT)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.ref("intValue"), "intValue"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.counters.containsKey(METRIC_NAME));
    assertEquals(48, listener.counters.get(METRIC_NAME).longValue());
  }

  @Test
  public void lenExpression() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    final String STRLEN_METRIC = "strlen";
    final ProbeId STRLEN_METRIC_ID = new ProbeId(STRLEN_METRIC, 1);
    final String LISTSIZE_METRIC = "listsize";
    final ProbeId LISTSIZE_METRIC_ID = new ProbeId(LISTSIZE_METRIC, 1);
    final String MAPSIZE_METRIC = "mapsize";
    final ProbeId MAPSIZE_METRIC_ID = new ProbeId(MAPSIZE_METRIC, 1);
    MetricProbe metricProbe1 =
        createMetricBuilder(STRLEN_METRIC_ID, STRLEN_METRIC, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.len(DSL.ref("strValue")), "len(strValue)"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbe2 =
        createMetricBuilder(LISTSIZE_METRIC_ID, LISTSIZE_METRIC, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.len(DSL.ref("strList")), "len(strList)"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbe3 =
        createMetricBuilder(MAPSIZE_METRIC_ID, MAPSIZE_METRIC, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.len(DSL.ref("strMap")), "len(strMap)"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener =
        installMetricProbes(metricProbe1, metricProbe2, metricProbe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.gauges.containsKey(STRLEN_METRIC));
    assertEquals(4, listener.gauges.get(STRLEN_METRIC).longValue()); // <=> "done".length()
    assertTrue(listener.gauges.containsKey(LISTSIZE_METRIC));
    assertEquals(3, listener.gauges.get(LISTSIZE_METRIC).longValue()); // <=> strList.size()
    assertTrue(listener.gauges.containsKey(MAPSIZE_METRIC));
    assertEquals(1, listener.gauges.get(MAPSIZE_METRIC).longValue()); // <=> strMap.size()
  }

  @Test
  public void nullExpression() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot06";
    String NULLLEN_METRIC = "nulllen";
    String MAPNULL_METRIC = "mapnull";
    String NULL_METRIC = "null";
    MetricProbe metricProbe1 =
        createMetricBuilder(METRIC_ID1, NULLLEN_METRIC, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.len(DSL.nullValue()), "len(null)"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbe2 =
        createMetricBuilder(METRIC_ID2, MAPNULL_METRIC, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(
                new ValueScript(DSL.index(DSL.ref("strMap"), DSL.nullValue()), "strMap[null]"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbe3 =
        createMetricBuilder(METRIC_ID3, NULL_METRIC, GAUGE)
            .where(CLASS_NAME, "f", "()")
            .valueScript(new ValueScript(DSL.nullValue(), "null"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener =
        installMetricProbes(metricProbe1, metricProbe2, metricProbe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertEquals(0, listener.gauges.size());
    ArgumentCaptor<String> strCaptor = ArgumentCaptor.forClass(String.class);
    verify(probeStatusSink, times(3)).addError(any(), strCaptor.capture());
    assertEquals(
        "Unsupported type for len operation: java.lang.Object", strCaptor.getAllValues().get(0));
    assertEquals(
        "Incompatible type for expression: java.lang.String with expected types: [long,double]",
        strCaptor.getAllValues().get(1));
    assertEquals(
        "Incompatible type for expression: java.lang.Object with expected types: [long,double]",
        strCaptor.getAllValues().get(2));
  }

  @Test
  public void indexExpression() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot22";
    final String ARRAYIDX_METRIC = "arrayindex";
    final ProbeId ARRAYIDX_METRIC_ID = new ProbeId(ARRAYIDX_METRIC, 1);
    final String LISTIDX_METRIC = "listindex";
    final ProbeId LISTIDX_METRIC_ID = new ProbeId(LISTIDX_METRIC, 1);
    final String MAPIDX_METRIC = "mapindex";
    final ProbeId MAPIDX_METRIC_ID = new ProbeId(MAPIDX_METRIC, 1);
    MetricProbe metricProbe1 =
        createMetricBuilder(ARRAYIDX_METRIC_ID, ARRAYIDX_METRIC, GAUGE)
            .where(CLASS_NAME, "doit", "(String)")
            .valueScript(
                new ValueScript(DSL.index(DSL.ref("intArray"), DSL.value(4)), "intArray[4]"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbe2 =
        createMetricBuilder(LISTIDX_METRIC_ID, LISTIDX_METRIC, GAUGE)
            .where(CLASS_NAME, "doit", "(String)")
            .valueScript(
                new ValueScript(
                    DSL.len(DSL.index(DSL.ref("strList"), DSL.value(1))), "len(strList[1])"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbe3 =
        createMetricBuilder(MAPIDX_METRIC_ID, MAPIDX_METRIC, GAUGE)
            .where(CLASS_NAME, "doit", "(String)")
            .valueScript(
                new ValueScript(
                    DSL.len(DSL.index(DSL.ref("strMap"), DSL.value("foo1"))),
                    "len(strMap['foo1'])"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener =
        installMetricProbes(metricProbe1, metricProbe2, metricProbe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertTrue(listener.gauges.containsKey(ARRAYIDX_METRIC));
    assertEquals(4, listener.gauges.get(ARRAYIDX_METRIC).longValue());
    assertTrue(listener.gauges.containsKey(LISTIDX_METRIC));
    assertEquals(3, listener.gauges.get(LISTIDX_METRIC).longValue());
    assertTrue(listener.gauges.containsKey(MAPIDX_METRIC));
    assertEquals(4, listener.gauges.get(MAPIDX_METRIC).longValue());
  }

  @Test
  public void indexInvalidKeyTypeExpression() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot22";
    final String ARRAYSTRIDX_METRIC = "arrayStrIndex";
    final ProbeId ARRAYSTRIDX_METRIC_ID = new ProbeId(ARRAYSTRIDX_METRIC, 1);
    final String ARRAYOOBIDX_METRIC = "arrayOutOfBoundsIndex";
    final ProbeId ARRAYOOBIDX_METRIC_ID = new ProbeId(ARRAYOOBIDX_METRIC, 1);
    MetricProbe metricProbe1 =
        createMetricBuilder(ARRAYSTRIDX_METRIC_ID, ARRAYSTRIDX_METRIC, GAUGE)
            .where(CLASS_NAME, "doit", "(String)")
            .valueScript(
                new ValueScript(
                    DSL.index(DSL.ref("intArray"), DSL.value("foo")), "intArray['foo']"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbe2 =
        createMetricBuilder(ARRAYOOBIDX_METRIC_ID, ARRAYOOBIDX_METRIC, GAUGE)
            .where(CLASS_NAME, "doit", "(String)")
            // generates ArrayOutOfBoundsException
            .valueScript(
                new ValueScript(DSL.index(DSL.ref("intArray"), DSL.value(42)), "intArray[42]"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe1, metricProbe2);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(42, result);
    assertFalse(listener.gauges.containsKey(ARRAYSTRIDX_METRIC));
    assertFalse(listener.gauges.containsKey(ARRAYOOBIDX_METRIC));
    verify(probeStatusSink)
        .addError(
            eq(ARRAYSTRIDX_METRIC_ID),
            eq(
                "Incompatible type for key: Type{mainType=Ljava/lang/String;, genericTypes=[]}, expected int or long"));
  }

  @Test
  public void privateFieldValue() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot04";
    final String STRVALUE_METRIC = "strvalue_count";
    final String LONGVALUE_METRIC = "longvalue_count";
    final String INTVALUE_METRIC = "intvalue_count";
    MetricProbe metricProbe1 =
        createMetricBuilder(METRIC_ID1, STRVALUE_METRIC, GAUGE)
            .where(CLASS_NAME, 24)
            .valueScript(
                new ValueScript(
                    DSL.len(DSL.getMember(DSL.ref("sdata"), "strValue")), "len(sdata.strValue)"))
            .build();
    MetricProbe metricProbe2 =
        createMetricBuilder(METRIC_ID2, LONGVALUE_METRIC, GAUGE)
            .where(CLASS_NAME, 24)
            .valueScript(
                new ValueScript(
                    DSL.getMember(DSL.getMember(DSL.ref("cdata"), "s2"), "longValue"),
                    "cdata.s2.longValue"))
            .build();
    MetricProbe metricProbe3 =
        createMetricBuilder(METRIC_ID3, INTVALUE_METRIC, GAUGE)
            .where(CLASS_NAME, 24)
            .valueScript(
                new ValueScript(
                    DSL.getMember(DSL.getMember(DSL.ref("cdata"), "s2"), "intValue"),
                    "cdata.s2.intValue"))
            .build();
    MetricForwarderListener listener =
        installMetricProbes(metricProbe1, metricProbe2, metricProbe3);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "f").get();
    assertEquals(143, result);
    assertEquals(3, listener.gauges.get(STRVALUE_METRIC));
    assertEquals(1042, listener.gauges.get(LONGVALUE_METRIC));
    assertEquals(202, listener.gauges.get(INTVALUE_METRIC));
  }

  @Test
  public void primitivesFunction() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot25";
    final String METRIC_NAME_INT = "int_arg_count";
    final ProbeId METRIC_INT_ID = new ProbeId(METRIC_NAME_INT, 1);
    final String METRIC_NAME_LONG = "long_arg_count";
    final ProbeId METRIC_LONG_ID = new ProbeId(METRIC_NAME_LONG, 1);
    final String METRIC_NAME_FLOAT = "float_arg_count";
    final ProbeId METRIC_FLOAT_ID = new ProbeId(METRIC_NAME_FLOAT, 1);
    final String METRIC_NAME_DOUBLE = "double_arg_count";
    final ProbeId METRIC_DOUBLE_ID = new ProbeId(METRIC_NAME_DOUBLE, 1);
    final String METRIC_NAME_BOOLEAN = "boolean_arg_count";
    final ProbeId METRIC_BOOLEAN_ID = new ProbeId(METRIC_NAME_BOOLEAN, 1);
    final String METRIC_NAME_BYTE = "byte_arg_count";
    final ProbeId METRIC_BYTE_ID = new ProbeId(METRIC_NAME_BYTE, 1);
    final String METRIC_NAME_SHORT = "short_arg_count";
    final ProbeId METRIC_SHORT_ID = new ProbeId(METRIC_NAME_SHORT, 1);
    final String METRIC_NAME_CHAR = "char_arg_count";
    final ProbeId METRIC_CHAR_ID = new ProbeId(METRIC_NAME_CHAR, 1);
    MetricProbe metricProbeInt =
        createMetricBuilder(METRIC_INT_ID, METRIC_NAME_INT, COUNT)
            .where(CLASS_NAME, "intFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbeLong =
        createMetricBuilder(METRIC_LONG_ID, METRIC_NAME_LONG, COUNT)
            .where(CLASS_NAME, "longFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbeFloat =
        createMetricBuilder(METRIC_FLOAT_ID, METRIC_NAME_FLOAT, GAUGE)
            .where(CLASS_NAME, "floatFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbeDouble =
        createMetricBuilder(METRIC_DOUBLE_ID, METRIC_NAME_DOUBLE, GAUGE)
            .where(CLASS_NAME, "doubleFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbeBoolean =
        createMetricBuilder(METRIC_BOOLEAN_ID, METRIC_NAME_BOOLEAN, COUNT)
            .where(CLASS_NAME, "booleanFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbeByte =
        createMetricBuilder(METRIC_BYTE_ID, METRIC_NAME_BYTE, COUNT)
            .where(CLASS_NAME, "byteFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbeShort =
        createMetricBuilder(METRIC_SHORT_ID, METRIC_NAME_SHORT, COUNT)
            .where(CLASS_NAME, "shortFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();
    MetricProbe metricProbeChar =
        createMetricBuilder(METRIC_CHAR_ID, METRIC_NAME_CHAR, COUNT)
            .where(CLASS_NAME, "charFunction")
            .valueScript(new ValueScript(DSL.ref("arg"), "arg"))
            .evaluateAt(MethodLocation.EXIT)
            .build();

    MetricForwarderListener listener =
        installMetricProbes(
            metricProbeInt,
            metricProbeLong,
            metricProbeFloat,
            metricProbeDouble,
            metricProbeBoolean,
            metricProbeByte,
            metricProbeShort,
            metricProbeChar);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "int").get();
    assertEquals(42, result);
    assertEquals(42, listener.counters.get(METRIC_NAME_INT));
    result = Reflect.on(testClass).call("main", "long").get();
    assertEquals(42, result);
    assertEquals(1001, listener.counters.get(METRIC_NAME_LONG));
    result = Reflect.on(testClass).call("main", "float").get();
    assertEquals(42, result);
    assertEquals(3.14, listener.doubleGauges.get(METRIC_NAME_FLOAT), 0.001);
    result = Reflect.on(testClass).call("main", "double").get();
    assertEquals(42, result);
    assertEquals(Math.E, listener.doubleGauges.get(METRIC_NAME_DOUBLE), 0.001);
    result = Reflect.on(testClass).call("main", "boolean").get();
    assertEquals(42, result);
    assertEquals(1, listener.counters.get(METRIC_NAME_BOOLEAN));
    result = Reflect.on(testClass).call("main", "byte").get();
    assertEquals(42, result);
    assertEquals(0x42, listener.counters.get(METRIC_NAME_BYTE));
    result = Reflect.on(testClass).call("main", "short").get();
    assertEquals(42, result);
    assertEquals(1001, listener.counters.get(METRIC_NAME_SHORT));
    result = Reflect.on(testClass).call("main", "char").get();
    assertEquals(42, result);
    assertEquals(97, listener.counters.get(METRIC_NAME_CHAR));
  }

  @Test
  public void localVarNotInScope() throws IOException, URISyntaxException {
    final String METRIC_NAME = "lenstr";
    final String CLASS_NAME = "com.datadog.debugger.jaxrs.MyResource";
    MetricProbe metricProbe =
        createMetricBuilder(METRIC_ID, METRIC_NAME, GAUGE)
            .where(CLASS_NAME, "createResource", null)
            .valueScript(new ValueScript(DSL.len(DSL.ref("varStr")), "len(varStr)"))
            .build();
    MetricForwarderListener listener = installMetricProbes(metricProbe);
    Class<?> testClass =
        loadClass(CLASS_NAME, getClass().getResource("/MyResource.class").getFile());
    Object result =
        Reflect.onClass(testClass)
            .create()
            .call("createResource", (Object) null, (Object) null, 1)
            .get();
    assertNotNull(result);
    assertFalse(listener.gauges.containsKey(METRIC_NAME));
  }

  private MetricForwarderListener installMethodMetric(
      String metricName,
      MetricProbe.MetricKind metricKind,
      String typeName,
      String methodName,
      String signature,
      ValueScript valueScript) {
    MetricProbe metricProbe =
        createMethodMetric(
            METRIC_ID, metricName, metricKind, typeName, methodName, signature, valueScript, null);
    return installMetricProbes(metricProbe);
  }

  private MetricForwarderListener installMethodMetric(
      String metricName,
      MetricProbe.MetricKind metricKind,
      String typeName,
      String methodName,
      String signature,
      ValueScript valueScript,
      String[] tags) {
    MetricProbe metricProbe =
        createMethodMetric(
            METRIC_ID, metricName, metricKind, typeName, methodName, signature, valueScript, tags);
    return installMetricProbes(metricProbe);
  }

  private static MetricProbe createMethodMetric(
      ProbeId id,
      String metricName,
      MetricProbe.MetricKind metricKind,
      String typeName,
      String methodName,
      String signature,
      ValueScript valueScript,
      String[] tags) {
    return createMetricBuilder(id, metricName, metricKind)
        .where(typeName, methodName, signature)
        .valueScript(valueScript)
        .tags(tags)
        .build();
  }

  private static MetricProbe createLineMetric(
      ProbeId id,
      String metricName,
      MetricProbe.MetricKind metricKind,
      String sourceFile,
      ValueScript valueScript,
      int line) {
    return createMetricBuilder(id, metricName, metricKind)
        .where(sourceFile, line)
        .valueScript(valueScript)
        .build();
  }

  private static MetricProbe.Builder createMetricBuilder(
      ProbeId id, String metricName, MetricProbe.MetricKind metricKind) {
    return MetricProbe.builder()
        .language(LANGUAGE)
        .probeId(id)
        .metricName(metricName)
        .kind(metricKind);
  }

  private MetricForwarderListener installMetricProbes(Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDynamicInstrumentationEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationClassFileDumpEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    probeStatusSink = mock(ProbeStatusSink.class);
    currentTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            null,
            new ProbeMetadata(),
            new DebuggerSink(config, probeStatusSink));
    instr.addTransformer(currentTransformer);
    MetricForwarderListener listener = new MetricForwarderListener();
    DebuggerContext.initMetricForwarder(listener);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    return listener;
  }

  private MetricForwarderListener installMetricProbes(MetricProbe... metricProbes) {
    return installMetricProbes(
        Configuration.builder().setService(SERVICE_NAME).add(metricProbes).build());
  }

  static class MetricForwarderListener implements DebuggerContext.MetricForwarder {
    Map<String, Long> counters = new HashMap<>();
    Map<String, Long> gauges = new HashMap<>();
    Map<String, Double> doubleGauges = new HashMap<>();
    Map<String, Long> histograms = new HashMap<>();
    Map<String, Double> doubleHistograms = new HashMap<>();
    Map<String, Long> distributions = new HashMap<>();
    Map<String, Double> doubleDistributions = new HashMap<>();
    String[] lastTags = null;
    boolean throwing;

    @Override
    public void count(String encodedProbeId, String name, long delta, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      counters.compute(name, (key, value) -> value != null ? value + delta : delta);
      lastTags = tags;
    }

    @Override
    public void gauge(String encodedProbeId, String name, long value, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      gauges.put(name, value);
      lastTags = tags;
    }

    @Override
    public void gauge(String encodedProbeId, String name, double value, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      doubleGauges.put(name, value);
      lastTags = tags;
    }

    @Override
    public void histogram(String encodedProbeId, String name, long value, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      histograms.put(name, value);
      lastTags = tags;
    }

    @Override
    public void histogram(String encodedProbeId, String name, double value, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      doubleHistograms.put(name, value);
      lastTags = tags;
    }

    @Override
    public void distribution(String encodedProbeId, String name, long value, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      distributions.put(name, value);
      lastTags = tags;
    }

    @Override
    public void distribution(String encodedProbeId, String name, double value, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      doubleDistributions.put(name, value);
      lastTags = tags;
    }

    public void setThrowing(boolean value) {
      this.throwing = value;
    }
  }
}
