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

import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.DebuggerMetrics;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.Snapshot;
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
  private static final String PROBE_ID = "12fd-8490-c111-4374-ffde";
  private static final String FIXTURE_PREFIX =
      "/" + DebuggerSinkTest.class.getPackage().getName().replaceAll("\\.", "/");

  private static final Snapshot.ProbeLocation PROBE_LOCATION =
      new Snapshot.ProbeLocation("java.lang.String", "indexOf", null, null);
  private static final Snapshot SNAPSHOT =
      new Snapshot(Thread.currentThread(), new Snapshot.ProbeDetails(PROBE_ID, PROBE_LOCATION));
  public static final int MAX_PAYLOAD = 5 * 1024 * 1024;
  private static final String EXPECTED_SNAPSHOT_TAGS =
      "^env:test,version:foo,debugger_version:0\\.\\d+\\.0-SNAPSHOT~[0-9a-f]{9},agent_version:null,host_name:"
          + Config.getHostName()
          + "$";

  @Mock private Config config;
  @Mock private BatchUploader batchUploader;
  @Captor private ArgumentCaptor<byte[]> payloadCaptor;

  @BeforeEach
  void setUp() {
    when(config.getServiceName()).thenReturn("service-name");
    when(config.getEnv()).thenReturn("test");
    when(config.getVersion()).thenReturn("foo");
    when(config.getDebuggerUploadBatchSize()).thenReturn(1);
  }

  @Test
  public void addSnapshot() throws URISyntaxException, IOException {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    sink.addSnapshot(SNAPSHOT);
    String fixtureContent = getFixtureContent(FIXTURE_PREFIX + "/snapshotRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex));
  }

  @Test
  public void addMultipleSnapshots() throws URISyntaxException, IOException {
    when(config.getDebuggerUploadBatchSize()).thenReturn(2);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Arrays.asList(SNAPSHOT, SNAPSHOT).forEach(sink::addSnapshot);

    String fixtureContent = getFixtureContent(FIXTURE_PREFIX + "/multipleSnapshotRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex));
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
    sink.addReceived("1");
    String fixtureContent = getFixtureContent(FIXTURE_PREFIX + "/diagnosticsRegex.txt");
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
      sink.addReceived(probeId);
    }

    String fixtureContent = getFixtureContent(FIXTURE_PREFIX + "/multipleDiagnosticsRegex.txt");
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
    StringBuilder largeMessageBuilder = new StringBuilder();
    for (int i = 0; i < 100_000; i++) {
      largeMessageBuilder.append("f");
    }
    String largeMessage = largeMessageBuilder.toString();
    for (int i = 0; i < 100; i++) {
      sink.getProbeDiagnosticsSink().addError(String.valueOf(i), largeMessage);
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
    StringBuilder tooLargeMessageBuilder = new StringBuilder();
    for (int i = 0; i < MAX_PAYLOAD; i++) {
      tooLargeMessageBuilder.append("f");
    }
    String tooLargeMessage = tooLargeMessageBuilder.toString();
    sink.getProbeDiagnosticsSink().addError("1", tooLargeMessage);
    sink.flush(sink);
    verifyNoInteractions(batchUploader);
  }

  @Test
  public void tooLargeUTF8Diagnostic() {
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    StringBuilder tooLargeMessageBuilder = new StringBuilder();
    for (int i = 0; i < MAX_PAYLOAD / 2; i++) {
      tooLargeMessageBuilder.append("\uD80C\uDCF0"); // 4 bytes
    }
    String tooLargeMessage = tooLargeMessageBuilder.toString();
    sink.getProbeDiagnosticsSink().addError("1", tooLargeMessage);
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
    sink.addSnapshot(SNAPSHOT);
    sink.doReconsiderFlushInterval();
    long newFlushInterval = sink.getCurrentFlushInterval();
    assertEquals(currentFlushInterval + DebuggerSink.STEP_SIZE, newFlushInterval);
  }

  @Test
  public void reconsiderFlushIntervalDecreaseFlushInterval() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    long currentFlushInterval = sink.getCurrentFlushInterval();
    sink.flush(sink);
    for (int i = 0; i < 1000; i++) {
      sink.addSnapshot(SNAPSHOT);
    }
    sink.doReconsiderFlushInterval();
    long newFlushInterval = sink.getCurrentFlushInterval();
    assertEquals(currentFlushInterval - DebuggerSink.STEP_SIZE, newFlushInterval);
  }

  @Test
  public void reconsiderFlushIntervalNoChange() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    long currentFlushInterval = sink.getCurrentFlushInterval();
    for (int i = 0; i < 500; i++) {
      sink.addSnapshot(SNAPSHOT);
    }
    sink.doReconsiderFlushInterval();
    long newFlushInterval = sink.getCurrentFlushInterval();
    assertEquals(currentFlushInterval, newFlushInterval);
  }

  @Test
  public void addSnapshotWithCorrelationIds() throws URISyntaxException, IOException {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    Snapshot.CapturedContext entry = new Snapshot.CapturedContext();
    entry.addFields(
        new Snapshot.CapturedValue[] {
          Snapshot.CapturedValue.of("dd.trace_id", "java.lang.String", "123"),
          Snapshot.CapturedValue.of("dd.span_id", "java.lang.String", "456"),
        });
    SNAPSHOT.getCaptures().setEntry(entry);
    sink.addSnapshot(SNAPSHOT);
    String fixtureContent =
        getFixtureContent(FIXTURE_PREFIX + "/snapshotWithCorrelationIdsRegex.txt");
    String regex = fixtureContent.replaceAll("\\n", "");
    sink.flush(sink);
    verify(batchUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.matches(regex), strPayload);
  }

  @Test
  public void addDiagnostic() {
    DebuggerSink sink = new DebuggerSink(config, batchUploader);
    DiagnosticMessage info = new DiagnosticMessage(DiagnosticMessage.Kind.INFO, "info message");
    DiagnosticMessage warn = new DiagnosticMessage(DiagnosticMessage.Kind.WARN, "info message");
    DiagnosticMessage error = new DiagnosticMessage(DiagnosticMessage.Kind.ERROR, "info message");
    sink.addDiagnostics(PROBE_ID, Arrays.asList(info, warn, error));
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
