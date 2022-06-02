package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.agent.DenyListHelper;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.Snapshot.CapturedContext;
import datadog.trace.bootstrap.debugger.Snapshot.CapturedValue;
import datadog.trace.bootstrap.debugger.Snapshot.ProbeDetails;
import datadog.trace.bootstrap.debugger.Snapshot.ProbeLocation;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SnapshotSummaryTest {
  private static final ProbeLocation PROBE_LOCATION =
      new ProbeLocation("com.datadog.debugger.SomeClass", "someMethod", null, null);
  private static final ProbeDetails PROBE_DETAILS =
      new ProbeDetails(UUID.randomUUID().toString(), PROBE_LOCATION);

  @BeforeEach
  public void setup() {
    // initialise the deny list so the ValueConverter works with most classes
    DebuggerContext.initClassFilter(new DenyListHelper(null));
  }

  @Test
  public void testSummaryEmptySnapshot() {
    Snapshot snapshot = new Snapshot(Thread.currentThread(), PROBE_DETAILS);
    assertEquals("SomeClass.someMethod()", SnapshotSummary.formatMessage(snapshot));
  }

  @Test
  public void testSummaryEntryExitSnapshot() {
    Snapshot snapshot = new Snapshot(Thread.currentThread(), PROBE_DETAILS);
    CapturedContext entry = new CapturedContext();
    entry.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", "java.lang.String", "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
          Snapshot.CapturedValue.of("arg3", "List", Arrays.asList("a", "b", "c"))
        });
    snapshot.setEntry(entry);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c])",
        SnapshotSummary.formatMessage(snapshot));

    CapturedContext exit = new CapturedContext();
    exit.addLocals(new Snapshot.CapturedValue[] {CapturedValue.of("@return", "double", 2.0)});
    snapshot.setExit(exit);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c]): 2.0\n"
            + "@return=2.0",
        SnapshotSummary.formatMessage(snapshot));
  }

  @Test
  public void testSummaryEntryExitSnapshotWithLocalVars() {
    Snapshot snapshot = new Snapshot(Thread.currentThread(), PROBE_DETAILS);
    CapturedContext entry = new CapturedContext();
    entry.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", "java.lang.String", "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
          Snapshot.CapturedValue.of("arg3", "List", Arrays.asList("a", "b", "c"))
        });
    entry.addLocals(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("str", "java.lang.String", "this is a local string"),
          Snapshot.CapturedValue.of("i", "int", 1001),
          Snapshot.CapturedValue.of("list", "List", Arrays.asList("1", "2", "3"))
        });
    snapshot.setEntry(entry);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c])\n"
            + "i=1001, list=[1, 2, 3], str=this is a local string",
        SnapshotSummary.formatMessage(snapshot));

    CapturedContext exit = new CapturedContext();
    exit.addLocals(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("str", "java.lang.String", "this is a local string"),
          Snapshot.CapturedValue.of("i", "int", 1001),
          Snapshot.CapturedValue.of("list", "List", Arrays.asList("1", "2", "3")),
          CapturedValue.of("@return", "double", 2.0)
        });
    snapshot.setExit(exit);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c]): 2.0\n"
            + "@return=2.0, i=1001, list=[1, 2, 3], str=this is a local string",
        SnapshotSummary.formatMessage(snapshot));
  }

  @Test
  public void testSummaryLineSnapshot() {
    Snapshot snapshot = new Snapshot(Thread.currentThread(), PROBE_DETAILS);
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    // top frame is actually getStackTrace, we want the test method
    StackTraceElement topFrame = stackTrace[1];
    StackTraceElement bottomFrame = stackTrace[stackTrace.length - 1];
    // top stack frame is used to get the method name we display
    snapshot.getStack().add(CapturedStackFrame.from(topFrame));
    // bottom stack frames are ignored in the summary
    snapshot.getStack().add(CapturedStackFrame.from(bottomFrame));

    CapturedContext lineCapture = new CapturedContext();
    lineCapture.addLocals(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("str", "java.lang.String", "this is a local string"),
          Snapshot.CapturedValue.of("i", "int", 1001),
          Snapshot.CapturedValue.of("list", "List", Arrays.asList("1", "2", "3"))
        });
    lineCapture.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", "java.lang.String", "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
        });
    snapshot.addLine(lineCapture, 23);

    // this is intentionally different from PROBE_DETAILS, the stacktrace should take precedence
    assertEquals(
        "SnapshotSummaryTest.testSummaryLineSnapshot(arg1=this is a string, arg2=42)\n"
            + "i=1001, list=[1, 2, 3], str=this is a local string",
        SnapshotSummary.formatMessage(snapshot));
  }

  @Test
  public void testUnexpectedStackFrameFormat() {
    Snapshot snapshot1 = new Snapshot(Thread.currentThread(), PROBE_DETAILS);
    snapshot1.getStack().add(new CapturedStackFrame("foobar", 123));
    assertEquals("foobar()", SnapshotSummary.formatMessage(snapshot1));

    Snapshot snapshot2 = new Snapshot(Thread.currentThread(), PROBE_DETAILS);
    snapshot2.getStack().add(new CapturedStackFrame("foobar()", 123));
    assertEquals("foobar()", SnapshotSummary.formatMessage(snapshot2));
  }

  @Test
  public void testLineProbeSummaryDisplay() {
    ProbeLocation location =
        new ProbeLocation(null, null, "SomeFile", Collections.singletonList("13"));
    // if the line probe had a stacktrace we would use the method information from the stacktrace
    Snapshot snapshot = new Snapshot(Thread.currentThread(), new ProbeDetails("id", location));

    CapturedContext lineCapture = new CapturedContext();
    lineCapture.addLocals(new Snapshot.CapturedValue[] {});
    lineCapture.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", "java.lang.String", "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
        });
    snapshot.addLine(lineCapture, 13);
    assertEquals(
        "SomeFile:[13](arg1=this is a string, arg2=42)", SnapshotSummary.formatMessage(snapshot));
  }
}
