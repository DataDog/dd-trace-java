package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.probe.LogProbe;
import datadog.trace.bootstrap.debugger.Snapshot;
import org.junit.jupiter.api.Test;

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
}
