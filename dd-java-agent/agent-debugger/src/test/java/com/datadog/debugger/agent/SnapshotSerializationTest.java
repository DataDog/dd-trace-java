package com.datadog.debugger.agent;

import static utils.TestHelper.getFixtureContent;

import com.datadog.debugger.sink.DebuggerSinkTest;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

public class SnapshotSerializationTest {

  private static final String PROBE_ID = "12fd-8490-c111-4374-ffde";

  private static final Snapshot.ProbeLocation PROBE_LOCATION =
      new Snapshot.ProbeLocation(
          "java.lang.String", "indexOf", "String.java", Arrays.asList("12-15", "23"));
  private static final String FIXTURE_PREFIX =
      "/" + DebuggerSinkTest.class.getPackage().getName().replaceAll("\\.", "/");

  @BeforeEach
  public void setup() {
    DebuggerContext.initClassFilter(new DenyListHelper(null));
  }

  @Test
  public void roundTripProbeLocation() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Snapshot.ProbeLocation location = deserializedSnapshot.getProbe().getLocation();
    Assert.assertEquals(PROBE_LOCATION.getType(), location.getType());
    Assert.assertEquals(PROBE_LOCATION.getFile(), location.getFile());
    Assert.assertEquals(PROBE_LOCATION.getMethod(), location.getMethod());
    Assert.assertEquals(PROBE_LOCATION.getLines(), location.getLines());
  }

  @Test
  @EnabledOnJre(JRE.JAVA_17)
  public void roundTripCapturedValue() throws IOException, URISyntaxException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext context = new Snapshot.CapturedContext();
    Snapshot.CapturedValue normalValuedField =
        Snapshot.CapturedValue.of("normalValuedField", "String", "foobar");
    Snapshot.CapturedValue normalNullField =
        Snapshot.CapturedValue.of("normalNullField", "String", null);
    Snapshot.CapturedValue notCapturedField =
        Snapshot.CapturedValue.reasonNotCaptured(
            "notCapturedField", "String", "InaccessibleObjectException");
    context.addFields(
        new Snapshot.CapturedValue[] {normalValuedField, normalNullField, notCapturedField});
    captures.setReturn(context);
    String buffer = adapter.toJson(snapshot);
    String snapshotRegex = getFixtureContent(FIXTURE_PREFIX + "/snapshotCapturedValueRegex.txt");
    snapshotRegex = snapshotRegex.replaceAll("\\n", "");
    Assert.assertTrue(buffer, buffer.matches(snapshotRegex));
    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<String, Snapshot.CapturedValue> fields =
        deserializedSnapshot.getCaptures().getReturn().getFields();
    Assert.assertEquals(3, fields.size());
    normalValuedField = fields.get("normalValuedField");
    Assert.assertEquals("foobar", normalValuedField.getValue());
    Assert.assertEquals("String", normalValuedField.getType());
    Assert.assertNull(normalValuedField.getReasonNotCaptured());
    normalNullField = fields.get("normalNullField");
    Assert.assertEquals("null", normalNullField.getValue());
    Assert.assertEquals("String", normalNullField.getType());
    Assert.assertNull(normalNullField.getReasonNotCaptured());
    notCapturedField = fields.get("notCapturedField");
    Assert.assertEquals("null", notCapturedField.getValue());
    Assert.assertEquals("String", notCapturedField.getType());
    Assert.assertEquals("InaccessibleObjectException", notCapturedField.getReasonNotCaptured());
  }

  @Test
  public void roundTripCaughtException() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    captures.addCaughtException(
        new Snapshot.CapturedThrowable(
            "java.lang.IllegalArgumentException",
            "illegal argument",
            Arrays.asList(
                new CapturedStackFrame("f1", 12),
                new CapturedStackFrame("f2", 23),
                new CapturedStackFrame("f3", 34))));
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    List<CapturedStackFrame> stacktrace =
        deserializedSnapshot.getCaptures().getCaughtExceptions().get(0).getStacktrace();
    Assert.assertEquals(3, stacktrace.size());
    assertCapturedFrame(stacktrace.get(0), "f1", 12);
    assertCapturedFrame(stacktrace.get(1), "f2", 23);
    assertCapturedFrame(stacktrace.get(2), "f3", 34);
  }

  @Test
  public void roundtripEntryReturn() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext entryCapturedContext = new Snapshot.CapturedContext();
    entryCapturedContext.addFields(
        new Snapshot.CapturedValue[] {Snapshot.CapturedValue.of("fieldInt", "int", "42")});
    captures.setEntry(entryCapturedContext);
    Snapshot.CapturedContext exitCapturedContext = new Snapshot.CapturedContext();
    exitCapturedContext.addFields(
        new Snapshot.CapturedValue[] {Snapshot.CapturedValue.of("fieldInt", "int", "42")});
    exitCapturedContext.addReturn(Snapshot.CapturedValue.of("java.lang.String", "foo"));
    captures.setReturn(exitCapturedContext);
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Snapshot.CapturedContext entry = deserializedSnapshot.getCaptures().getEntry();
    Snapshot.CapturedContext exit = deserializedSnapshot.getCaptures().getReturn();
    Assert.assertEquals(1, entry.getFields().size());
    Assert.assertEquals("42", entry.getFields().get("fieldInt").getValue());
    Assert.assertEquals(1, exit.getFields().size());
    Assert.assertEquals("42", exit.getFields().get("fieldInt").getValue());
    Assert.assertEquals("foo", exit.getLocals().get("@return").getValue());
  }

  @Test
  public void roundtripLines() throws IOException {
    JsonAdapter<Snapshot> adapter = MoshiHelper.createMoshiSnapshot().adapter(Snapshot.class);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Snapshot.Captures captures = snapshot.getCaptures();
    Snapshot.CapturedContext lineCapturedContext = new Snapshot.CapturedContext();
    lineCapturedContext.addFields(
        new Snapshot.CapturedValue[] {Snapshot.CapturedValue.of("fieldInt", "int", "42")});
    captures.addLine(24, lineCapturedContext);
    String buffer = adapter.toJson(snapshot);

    Snapshot deserializedSnapshot = adapter.fromJson(buffer);
    Map<Integer, Snapshot.CapturedContext> lines = deserializedSnapshot.getCaptures().getLines();
    Assert.assertEquals(1, lines.size());
    Map<String, Snapshot.CapturedValue> lineFields = lines.get(24).getFields();
    Assert.assertEquals(1, lineFields.size());
    Assert.assertEquals("42", lineFields.get("fieldInt").getValue());
  }

  private void assertCapturedFrame(
      CapturedStackFrame capturedStackFrame, String methodName, int lineNumber) {
    Assert.assertNull(capturedStackFrame.getFileName());
    Assert.assertEquals(methodName, capturedStackFrame.getFunction());
    Assert.assertEquals(lineNumber, capturedStackFrame.getLineNumber());
  }
}
