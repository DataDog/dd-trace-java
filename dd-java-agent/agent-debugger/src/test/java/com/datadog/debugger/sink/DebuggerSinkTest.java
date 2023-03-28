package com.datadog.debugger.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static utils.TestHelper.getFixtureContent;

import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.DebuggerMetrics;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.*;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DebuggerSinkTest {
  public static final String SINK_FIXTURE_PREFIX = "/com/datadog/debugger/sink";

  private static final String PROBE_ID = "12fd-8490-c111-4374-ffde";
  private static final Snapshot.ProbeLocation PROBE_LOCATION =
      new Snapshot.ProbeLocation("java.lang.String", "indexOf", null, null);
  public static final int MAX_PAYLOAD = 5 * 1024 * 1024;

  @Mock private Config config;
  @Mock private BatchUploader batchUploader;
  @Captor private ArgumentCaptor<byte[]> payloadCaptor;

  private String EXPECTED_SNAPSHOT_TAGS;

  @BeforeEach
  void setUp() {
    DebuggerContext.initSnapshotSerializer(new JsonSnapshotSerializer());
    when(config.getHostName()).thenReturn("host-name");
    when(config.getServiceName()).thenReturn("service-name");
    when(config.getEnv()).thenReturn("test");
    when(config.getVersion()).thenReturn("foo");
    when(config.getDebuggerUploadBatchSize()).thenReturn(1);

    EXPECTED_SNAPSHOT_TAGS =
        "^env:test,version:foo,debugger_version:\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?~[0-9a-f]+,agent_version:null,host_name:"
            + config.getHostName()
            + "$";
  }

  @Test
  public void addSnapshot() throws URISyntaxException, IOException {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    sink.addSnapshot(snapshot);
    String fixtureContent = getFixtureContent(SINK_FIXTURE_PREFIX + "/snapshotRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  @Test
  public void addMultipleSnapshots() throws URISyntaxException, IOException {
    when(config.getDebuggerUploadBatchSize()).thenReturn(2);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    Arrays.asList(snapshot, snapshot).forEach(sink::addSnapshot);

    String fixtureContent = getFixtureContent(SINK_FIXTURE_PREFIX + "/multipleSnapshotRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  @Test
  public void splitSnapshotBatch() {
    when(config.getDebuggerUploadBatchSize()).thenReturn(10);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot largeSnapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    for (int i = 0; i < 15_000; i++) {
      largeSnapshot.getStack().add(new CapturedStackFrame("f" + i, i));
    }
    for (int i = 0; i < 10; i++) {
      sink.addSnapshot(largeSnapshot);
    }
    sink.flush(sink);
    verify(batchUploader, times(2))
        .upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    Assertions.assertTrue(payloadCaptor.getAllValues().get(0).length < MAX_PAYLOAD);
    Assertions.assertTrue(payloadCaptor.getAllValues().get(1).length < MAX_PAYLOAD);
  }

  @Test
  public void tooLargeSnapshot() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot largeSnapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    for (int i = 0; i < 150_000; i++) {
      largeSnapshot.getStack().add(new CapturedStackFrame("f" + i, i));
    }
    sink.addSnapshot(largeSnapshot);
    sink.flush(sink);
    verifyNoInteractions(batchUploader);
  }

  @Test
  public void tooLargeUTF8Snapshot() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot largeSnapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    for (int i = 0; i < 140_000; i++) {
      largeSnapshot.getStack().add(new CapturedStackFrame("f€" + i, i));
    }
    sink.addSnapshot(largeSnapshot);
    sink.flush(sink);
    verifyNoInteractions(batchUploader);
  }

  @Test
  public void addNoSnapshots() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    sink.flush(sink);
    verifyNoInteractions(batchUploader);
  }

  @Test
  public void addDiagnostics() throws URISyntaxException, IOException {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    sink.addReceived(new ProbeId("1", 2));
    String fixtureContent = getFixtureContent(SINK_FIXTURE_PREFIX + "/diagnosticsRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  @Test
  public void addMultipleDiagnostics() throws URISyntaxException, IOException {
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    for (String probeId : Arrays.asList("1", "2")) {
      sink.addReceived(new ProbeId(probeId, 1));
    }

    String fixtureContent =
        getFixtureContent(SINK_FIXTURE_PREFIX + "/multipleDiagnosticsRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  @Test
  public void splitDiagnosticsBatch() {
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    StringBuilder largeMessageBuilder = new StringBuilder(100_001);
    for (int i = 0; i < 100_000; i++) {
      largeMessageBuilder.append("f");
    }
    String largeMessage = largeMessageBuilder.toString();
    for (int i = 0; i < 100; i++) {
      sink.getProbeDiagnosticsSink().addError(new ProbeId(String.valueOf(i), i), largeMessage);
    }
    sink.flush(sink);
    verify(batchUploader, times(2))
        .upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    Assertions.assertTrue(payloadCaptor.getAllValues().get(0).length < MAX_PAYLOAD);
    Assertions.assertTrue(payloadCaptor.getAllValues().get(1).length < MAX_PAYLOAD);
  }

  @Test
  public void tooLargeDiagnostic() {
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    StringBuilder tooLargeMessageBuilder = new StringBuilder(MAX_PAYLOAD + 1);
    for (int i = 0; i < MAX_PAYLOAD; i++) {
      tooLargeMessageBuilder.append("f");
    }
    String tooLargeMessage = tooLargeMessageBuilder.toString();
    sink.getProbeDiagnosticsSink().addError(new ProbeId("1", 1), tooLargeMessage);
    sink.flush(sink);
    verifyNoInteractions(batchUploader);
  }

  @Test
  public void tooLargeUTF8Diagnostic() {
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    StringBuilder tooLargeMessageBuilder = new StringBuilder(MAX_PAYLOAD + 4);
    for (int i = 0; i < MAX_PAYLOAD; i += 4) {
      tooLargeMessageBuilder.append("\uD80C\uDCF0"); // 4 bytes
    }
    String tooLargeMessage = tooLargeMessageBuilder.toString();
    sink.getProbeDiagnosticsSink().addError(new ProbeId("1", 1), tooLargeMessage);
    sink.flush(sink);
    verifyNoInteractions(batchUploader);
  }

  @Test
  public void addNoDiagnostic() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    sink.flush(sink);
    verifyNoInteractions(batchUploader);
  }

  @Test
  public void reconsiderFlushIntervalIncreaseFlushInterval() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    long currentFlushInterval = sink.getCurrentFlushInterval();
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    sink.addSnapshot(snapshot);
    sink.doReconsiderFlushInterval();
    long newFlushInterval = sink.getCurrentFlushInterval();
    assertEquals(currentFlushInterval + DebuggerSink.STEP_SIZE, newFlushInterval);
  }

  @Test
  public void reconsiderFlushIntervalDecreaseFlushInterval() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    long currentFlushInterval = sink.getCurrentFlushInterval();
    sink.flush(sink);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    for (int i = 0; i < 1000; i++) {
      sink.addSnapshot(snapshot);
    }
    sink.doReconsiderFlushInterval();
    long newFlushInterval = sink.getCurrentFlushInterval();
    assertEquals(currentFlushInterval - DebuggerSink.STEP_SIZE, newFlushInterval);
  }

  @Test
  public void reconsiderFlushIntervalNoChange() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    long currentFlushInterval = sink.getCurrentFlushInterval();
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    for (int i = 0; i < 500; i++) {
      sink.addSnapshot(snapshot);
    }
    sink.doReconsiderFlushInterval();
    long newFlushInterval = sink.getCurrentFlushInterval();
    assertEquals(currentFlushInterval, newFlushInterval);
  }

  @Test
  public void addSnapshotWithCorrelationIdsMethodProbe() throws URISyntaxException, IOException {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot.CapturedContext entry = new Snapshot.CapturedContext();
    entry.addFields(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("dd.trace_id", "java.lang.String", "123"),
          Snapshot.CapturedValue.of("dd.span_id", "java.lang.String", "456"),
        });
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    snapshot.setEntry(entry);
    sink.addSnapshot(snapshot);
    String fixtureContent =
        getFixtureContent(SINK_FIXTURE_PREFIX + "/snapshotWithCorrelationIdsRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  @Test
  public void addSnapshotWithCorrelationIdsLineProbe() throws URISyntaxException, IOException {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot.CapturedContext line = new Snapshot.CapturedContext();
    line.addFields(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("dd.trace_id", "java.lang.String", "123"),
          Snapshot.CapturedValue.of("dd.span_id", "java.lang.String", "456"),
        });
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    snapshot.addLine(line, 25);
    sink.addSnapshot(snapshot);
    String fixtureContent =
        getFixtureContent(SINK_FIXTURE_PREFIX + "/snapshotWithCorrelationIdsLineRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  @Test
  public void addSnapshotWithEvalErrors() throws URISyntaxException, IOException {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    DebuggerContext.init(sink, null, null);
    Snapshot.CapturedContext entry = new Snapshot.CapturedContext();
    Snapshot.ProbeDetails probeDetails = new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION);
    Snapshot snapshot = new Snapshot(Thread.currentThread(), probeDetails);
    snapshot.setEntry(entry);
    snapshot.addEvaluationErrors(
        Arrays.asList(new Snapshot.EvaluationError("obj.field", "Cannot dereference obj")));
    commit(snapshot);
    snapshot.getStack().clear();
    String fixtureContent =
        getFixtureContent(SINK_FIXTURE_PREFIX + "/snapshotWithEvalErrorRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  // used for increasing the stack level for the getting the excepted stack trace
  private static void commit(Snapshot snapshot) {
    snapshot.commit();
  }

  @Test
  public void addDiagnostic() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    DiagnosticMessage info = new DiagnosticMessage(DiagnosticMessage.Kind.INFO, "info message");
    DiagnosticMessage warn = new DiagnosticMessage(DiagnosticMessage.Kind.WARN, "info message");
    DiagnosticMessage error = new DiagnosticMessage(DiagnosticMessage.Kind.ERROR, "info message");
    sink.addDiagnostics(new ProbeId(PROBE_ID, 1), Arrays.asList(info, warn, error));
    // ensure just that the code is executed to have coverage (just logging)
  }

  @Test
  public void skipSnapshot() {
    DebuggerMetrics debuggerMetrics = spy(DebuggerMetrics.getInstance(config));
    DebuggerSink sink = new DebuggerSink(config, batchUploader, debuggerMetrics);
    Snapshot snapshot =
        new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
    sink.skipSnapshot(snapshot.getProbe().getId(), DebuggerContext.SkipCause.CONDITION);
    verify(debuggerMetrics)
        .incrementCounter(anyString(), eq("cause:condition"), eq("probe_id:" + PROBE_ID));
    sink.skipSnapshot(snapshot.getProbe().getId(), DebuggerContext.SkipCause.RATE);
    verify(debuggerMetrics)
        .incrementCounter(anyString(), eq("cause:rate"), eq("probe_id:" + PROBE_ID));
  }
}
