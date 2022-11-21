package datadog.trace.bootstrap.debugger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.bootstrap.debugger.Snapshot.CapturedContext;
import datadog.trace.bootstrap.debugger.Snapshot.CapturedValue;
import datadog.trace.bootstrap.debugger.Snapshot.ProbeDetails;
import datadog.trace.bootstrap.debugger.Snapshot.ProbeLocation;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SnapshotSummaryTest {
  private static final String CLASS_NAME = "com.datadog.debugger.SomeClass";
  private static final ProbeLocation PROBE_LOCATION =
      new ProbeLocation(CLASS_NAME, "someMethod", null, null);

  @BeforeAll
  public static void staticSetup() {
    DebuggerContext.initSnapshotSerializer(null);
  }

  @Test
  public void testSummaryEmptySnapshot() {
    Snapshot snapshot =
        new Snapshot(
            Thread.currentThread(),
            new ProbeDetails(UUID.randomUUID().toString(), PROBE_LOCATION),
            CLASS_NAME);
    assertEquals("SomeClass.someMethod()", snapshot.getSummary());
  }

  @Test
  public void testSummaryEntryExitSnapshot() {
    Snapshot snapshot =
        new Snapshot(
            Thread.currentThread(),
            new ProbeDetails(UUID.randomUUID().toString(), PROBE_LOCATION),
            CLASS_NAME);
    CapturedContext entry = new CapturedContext();
    HashMap<String, String> argMap = new HashMap<>();
    argMap.put("foo", "bar");
    entry.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", String.class.getTypeName(), "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
          Snapshot.CapturedValue.of("arg3", List.class.getTypeName(), Arrays.asList("a", "b", "c")),
          Snapshot.CapturedValue.of("arg4", Map.class.getTypeName(), argMap)
        });
    snapshot.setEntry(entry);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c], arg4={foo=bar})",
        snapshot.getSummary());

    CapturedContext exit = new CapturedContext();
    exit.addLocals(new Snapshot.CapturedValue[] {CapturedValue.of("@return", "double", 2.0)});
    snapshot.setExit(exit);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c], arg4={foo=bar}): 2.0",
        snapshot.getSummary());
  }

  @Test
  public void testSummaryEntryExitSnapshotWithLocalVars() {
    Snapshot snapshot =
        new Snapshot(
            Thread.currentThread(),
            new ProbeDetails(UUID.randomUUID().toString(), PROBE_LOCATION),
            CLASS_NAME);
    CapturedContext entry = new CapturedContext();
    entry.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", String.class.getTypeName(), "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
          Snapshot.CapturedValue.of("arg3", List.class.getTypeName(), Arrays.asList("a", "b", "c"))
        });
    snapshot.setEntry(entry);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c])",
        snapshot.getSummary());

    CapturedContext exit = new CapturedContext();
    exit.addLocals(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("str", String.class.getTypeName(), "this is a local string"),
          Snapshot.CapturedValue.of("i", "int", 1001),
          Snapshot.CapturedValue.of("list", List.class.getTypeName(), Arrays.asList("1", "2", "3")),
          CapturedValue.of("@return", "double", 2.0)
        });
    snapshot.setExit(exit);
    assertEquals(
        "SomeClass.someMethod(arg1=this is a string, arg2=42, arg3=[a, b, c]): 2.0\n"
            + "i=1001, list=[1, 2, 3], str=this is a local string",
        snapshot.getSummary());
  }

  @Test
  public void testSummaryLineSnapshot() {
    Snapshot snapshot =
        new Snapshot(
            Thread.currentThread(),
            new ProbeDetails(UUID.randomUUID().toString(), PROBE_LOCATION),
            CLASS_NAME);
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
          Snapshot.CapturedValue.of("str", String.class.getTypeName(), "this is a local string"),
          Snapshot.CapturedValue.of("i", "int", 1001),
          Snapshot.CapturedValue.of("list", List.class.getTypeName(), Arrays.asList("1", "2", "3"))
        });
    lineCapture.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", String.class.getTypeName(), "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
        });
    snapshot.addLine(lineCapture, 23);
    snapshot.commit();

    // this is intentionally different from PROBE_DETAILS, the stacktrace should take precedence
    assertEquals(
        "SnapshotSummaryTest.testSummaryLineSnapshot(arg1=this is a string, arg2=42)\n"
            + "i=1001, list=[1, 2, 3], str=this is a local string",
        snapshot.getSummary());
  }

  @Test
  public void testUnexpectedStackFrameFormat() {
    SnapshotSummaryBuilder snapshotSummaryBuilder = new SnapshotSummaryBuilder(PROBE_LOCATION);
    snapshotSummaryBuilder.addStack(Arrays.asList(new CapturedStackFrame("foobar", 123)));
    assertEquals("foobar()", snapshotSummaryBuilder.build());

    SnapshotSummaryBuilder snapshotSummaryBuilder2 = new SnapshotSummaryBuilder(PROBE_LOCATION);
    snapshotSummaryBuilder2.addStack(Arrays.asList(new CapturedStackFrame("foobar()", 123)));
    assertEquals("foobar()", snapshotSummaryBuilder2.build());
  }

  @Test
  public void testLineProbeSummaryDisplay() {
    ProbeLocation location =
        new ProbeLocation(null, null, "SomeFile", Collections.singletonList("13"));
    // if the line probe had a stacktrace we would use the method information from the stacktrace
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new ProbeDetails("id", location), CLASS_NAME);

    CapturedContext lineCapture = new CapturedContext();
    lineCapture.addLocals(new Snapshot.CapturedValue[] {});
    lineCapture.addArguments(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("arg1", String.class.getTypeName(), "this is a string"),
          Snapshot.CapturedValue.of("arg2", "int", 42),
        });
    snapshot.addLine(lineCapture, 13);
    assertEquals("SomeFile:[13](arg1=this is a string, arg2=42)", snapshot.getSummary());
  }
}
