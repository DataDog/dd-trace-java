package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.probe.LogProbe;
import datadog.trace.bootstrap.debugger.Snapshot;
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

class LogMessageTemplateSummaryBuilderTest {

  @Test
  public void emptyProbe() {
    LogProbe probe = LogProbe.builder().build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    summaryBuilder.addEntry(new Snapshot.CapturedContext());
    assertEquals("This is a dynamically created log message.", summaryBuilder.build());
  }

  @Test
  public void emptyTemplate() {
    LogProbe probe = LogProbe.builder().template("").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    summaryBuilder.addEntry(new Snapshot.CapturedContext());
    assertEquals("", summaryBuilder.build());
  }

  @Test
  public void onlyStringTemplate() {
    LogProbe probe = LogProbe.builder().template("this is a simple string").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    summaryBuilder.addEntry(new Snapshot.CapturedContext());
    assertEquals("this is a simple string", summaryBuilder.build());
  }

  @Test
  public void undefinedArgTemplate() {
    LogProbe probe = LogProbe.builder().template("{arg}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    summaryBuilder.addEntry(new Snapshot.CapturedContext());
    assertEquals("UNDEFINED", summaryBuilder.build());
  }

  @Test
  public void argTemplate() {
    LogProbe probe = LogProbe.builder().template("{arg}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
    capturedContext.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg", String.class.getTypeName(), "foo")
        });
    summaryBuilder.addEntry(capturedContext);
    assertEquals("foo", summaryBuilder.build());
  }

  @Test
  public void argNullTemplate() {
    LogProbe probe = LogProbe.builder().template("{nullObject}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
    capturedContext.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("nullObject", Object.class.getTypeName(), null)
        });
    summaryBuilder.addEntry(capturedContext);
    assertEquals("null", summaryBuilder.build());
  }

  @Test
  public void argArrayTemplate() {
    LogProbe probe = LogProbe.builder().template("{primArray} {strArray}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
    capturedContext.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of(
              "primArray", String.class.getTypeName(), new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}),
          Snapshot.CapturedValue.of(
              "strArray",
              String.class.getTypeName(),
              new String[] {
                "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8", "foo9"
              })
        });
    summaryBuilder.addEntry(capturedContext);
    assertEquals("[0, 1, 2, ...] [foo0, foo1, foo2, ...]", summaryBuilder.build());
  }

  @Test
  public void argCollectionTemplate() {
    LogProbe probe = LogProbe.builder().template("{strList} {strSet}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
    capturedContext.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of(
              "strList",
              String.class.getTypeName(),
              new ArrayList<>(
                  Arrays.asList(
                      "foo0", "foo1", "foo2", "foo3", "foo4", "foo5", "foo6", "foo7", "foo8",
                      "foo9"))),
          Snapshot.CapturedValue.of(
              "strSet",
              String.class.getTypeName(),
              new LinkedHashSet<>(
                  Arrays.asList(
                      "bar0", "bar1", "bar2", "bar3", "bar4", "bar5", "bar6", "bar7", "bar8",
                      "bar9")))
        });
    summaryBuilder.addEntry(capturedContext);
    assertEquals("[foo0, foo1, foo2, ...] [bar0, bar1, bar2, ...]", summaryBuilder.build());
  }

  @Test
  public void argMapTemplate() {
    LogProbe probe = LogProbe.builder().template("{strMap}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
    Map<String, String> map = new LinkedHashMap<>();
    for (int i = 0; i < 10; i++) {
      map.put("foo" + i, "bar" + i);
    }
    capturedContext.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("strMap", String.class.getTypeName(), map)
        });
    summaryBuilder.addEntry(capturedContext);
    assertEquals("{[foo0=bar0], [foo1=bar1], [foo2=bar2], ...}", summaryBuilder.build());
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
    LogProbe probe = LogProbe.builder().template("{obj}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
    capturedContext.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("obj", Level0.class.getTypeName(), new Level0())
        });
    summaryBuilder.addEntry(capturedContext);
    assertEquals("{intField0=0, strField0=foo0, level1=...}", summaryBuilder.build());
  }

  @Test
  @EnabledOnJre(JRE.JAVA_17)
  public void argInaccessibleFieldTemplate() {
    LogProbe probe = LogProbe.builder().template("{obj}").build();
    LogMessageTemplateSummaryBuilder summaryBuilder = new LogMessageTemplateSummaryBuilder(probe);
    Snapshot.CapturedContext capturedContext = new Snapshot.CapturedContext();
    capturedContext.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of(
              "obj", Object.class.getTypeName(), ManagementFactory.getOperatingSystemMXBean())
        });
    summaryBuilder.addEntry(capturedContext);
    assertEquals(
        "{containerMetrics=UNDEFINED, systemLoadTicks=UNDEFINED, processLoadTicks=UNDEFINED, jvm=UNDEFINED, loadavg=UNDEFINED}",
        summaryBuilder.build());
    List<Snapshot.EvaluationError> evaluationErrors = summaryBuilder.getEvaluationErrors();
    assertEquals(5, evaluationErrors.size());
    for (int i = 0; i < 5; i++) {
      assertTrue(evaluationErrors.get(i).getMessage().contains("Cannot extract field:"));
    }
  }
}
