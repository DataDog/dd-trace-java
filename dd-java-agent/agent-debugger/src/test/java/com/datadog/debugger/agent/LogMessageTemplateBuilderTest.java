package com.datadog.debugger.agent;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

class LogMessageTemplateBuilderTest {
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);

  @Test
  public void emptyProbe() {
    LogProbe probe = LogProbe.builder().build();
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    String message = summaryBuilder.evaluate(new CapturedContext(), new LogProbe.LogStatus(probe));
    assertNull(message);
  }

  @Test
  public void emptyTemplate() {
    LogProbe probe = createLogProbe("");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    String message = summaryBuilder.evaluate(new CapturedContext(), new LogProbe.LogStatus(probe));
    assertEquals("", message);
  }

  @Test
  public void onlyStringTemplate() {
    LogProbe probe = createLogProbe("this is a simple string");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    String message = summaryBuilder.evaluate(new CapturedContext(), new LogProbe.LogStatus(probe));
    assertEquals("this is a simple string", message);
  }

  @Test
  public void undefinedArgTemplate() {
    ProbeStatusSink probeStatusSink = mock(ProbeStatusSink.class);
    DebuggerAgent.initSink(new DebuggerSink(Config.get(), probeStatusSink));
    LogProbe probe = createLogProbe("{arg}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    String message = summaryBuilder.evaluate(new CapturedContext(), new LogProbe.LogStatus(probe));
    assertEquals("", message);
    verify(probeStatusSink).addError(eq(PROBE_ID), eq("Cannot find symbol: arg"));
  }

  @Test
  public void argTemplate() {
    LogProbe probe = createLogProbe("{arg}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("arg", String.class.getTypeName(), "foo")
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    assertEquals("foo", message);
  }

  @Test
  public void argMultipleInFlightTemplate() {
    LogProbe probe = createLogProbe("{arg}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("arg", String.class.getTypeName(), "foo")
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    LogMessageTemplateBuilder summaryBuilder2 =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext2 = new CapturedContext();
    capturedContext2.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("arg", String.class.getTypeName(), "bar")
        });
    String message2 = summaryBuilder2.evaluate(capturedContext2, new LogProbe.LogStatus(probe));
    assertEquals("foo", message);
    assertEquals("bar", message2);
  }

  @Test
  public void argNullTemplate() {
    LogProbe probe = createLogProbe("{nullObject}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("nullObject", Object.class.getTypeName(), null)
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    assertEquals("null", message);
  }

  @Test
  public void argArrayTemplate() {
    LogProbe probe = createLogProbe("{primArray} {strArray}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of(
              "primArray", String.class.getTypeName(), new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}),
          CapturedContext.CapturedValue.of(
              "strArray",
              String.class.getTypeName(),
              new String[] {
                "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8", "foo9"
              })
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    assertEquals("[0, 1, 2, ...] [foo0, foo1, foo2, ...]", message);
  }

  @Test
  public void argCollectionTemplate() {
    LogProbe probe = createLogProbe("{strList} {strSet}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of(
              "strList",
              String.class.getTypeName(),
              new ArrayList<>(
                  Arrays.asList(
                      "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8",
                      "foo9"))),
          CapturedContext.CapturedValue.of(
              "strSet",
              String.class.getTypeName(),
              new LinkedHashSet<>(
                  Arrays.asList(
                      "bar0", "bar1", "bar2", "bar3", "bar4", "bar5", "bar6", "bar7", "bar8",
                      "bar9")))
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    assertEquals("[foo0, foo1, foo2, ...] [bar0, bar1, bar2, ...]", message);
  }

  @Test
  public void argMapTemplate() {
    LogProbe probe = createLogProbe("{strMap}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < 10; i++) {
      map.put("foo" + i, "bar" + i);
    }
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("strMap", String.class.getTypeName(), map)
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    assertEquals("{[foo0=bar0], [foo1=bar1], [foo2=bar2], ...}", message);
  }

  static class Level0 {
    int intField0 = 0;
    String strField0 = "foo0";
    Level1 level1 = new Level1();
  }

  static class Level1 {
    int intField1 = 1;
    String strField1 = "foo1";
  }

  @Test
  public void argComplexObjectTemplate() {
    LogProbe probe = createLogProbe("{obj}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of("obj", Level0.class.getTypeName(), new Level0())
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    assertEquals("{intField0=0, strField0=foo0, level1=...}", message);
  }

  @Test
  public void argComplexObjectArrayTemplate() {
    LogProbe probe = createLogProbe("{array}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of(
              "array", Level0[].class.getTypeName(), new Level0[] {new Level0(), new Level0()})
        });
    String message = summaryBuilder.evaluate(capturedContext, new LogProbe.LogStatus(probe));
    assertEquals("[..., ...]", message);
  }

  @Test
  @EnabledOnJre(JRE.JAVA_17)
  public void argInaccessibleFieldTemplate() {
    LogProbe probe = createLogProbe("{obj}");
    LogMessageTemplateBuilder summaryBuilder =
        new LogMessageTemplateBuilder(PROBE_ID, probe.getSegments());
    CapturedContext capturedContext = new CapturedContext();
    capturedContext.addArguments(
        new CapturedContext.CapturedValue[] {
          CapturedContext.CapturedValue.of(
              "obj", Object.class.getTypeName(), ManagementFactory.getOperatingSystemMXBean())
        });
    LogProbe.LogStatus status = new LogProbe.LogStatus(probe);
    String message = summaryBuilder.evaluate(capturedContext, status);
    assertEquals(
        "{containerMetrics=UNDEFINED, systemLoadTicks=UNDEFINED, processLoadTicks=UNDEFINED, jvm=UNDEFINED, loadavg=UNDEFINED}",
        message);
    List<EvaluationError> evaluationErrors = status.getErrors();
    assertEquals(5, evaluationErrors.size());
    for (int i = 0; i < 5; i++) {
      assertTrue(evaluationErrors.get(i).getMessage().contains("Cannot extract field:"));
    }
  }

  private LogProbe createLogProbe(String template) {
    return LogProbe.builder().template(template, parseTemplate(template)).build();
  }
}
