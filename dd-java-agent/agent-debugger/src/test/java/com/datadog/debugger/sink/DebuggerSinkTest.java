package com.datadog.debugger.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.DebuggerMetrics;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.CapturedContext.CapturedValue;
import datadog.trace.bootstrap.debugger.CapturedStackFrame;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.EvaluationError;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DebuggerSinkTest {
  private static final ProbeId PROBE_ID = new ProbeId("12fd-8490-c111-4374-ffde", 42);
  private static final ProbeLocation PROBE_LOCATION =
      new ProbeLocation("java.lang.String", "indexOf", null, null);
  public static final int MAX_PAYLOAD = 5 * 1024 * 1024;

  @Mock private Config config;
  @Mock private BatchUploader snapshotUploader;
  @Mock private BatchUploader logUploader;
  @Captor private ArgumentCaptor<byte[]> payloadCaptor;

  private String EXPECTED_SNAPSHOT_TAGS;
  private ProbeStatusSink probeStatusSink;

  @BeforeEach
  void setUp() {
    JsonSnapshotSerializer jsonSnapshotSerializer = new JsonSnapshotSerializer();
    DebuggerContext.initValueSerializer(jsonSnapshotSerializer);
    DebuggerAgentHelper.injectSerializer(jsonSnapshotSerializer);
    when(config.getHostName()).thenReturn("host-name");
    when(config.getServiceName()).thenReturn("service-name");
    when(config.getEnv()).thenReturn("test");
    when(config.getVersion()).thenReturn("foo");
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(1);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");

    EXPECTED_SNAPSHOT_TAGS =
        "^env:test,version:foo,debugger_version:\\d+\\.\\d+\\.\\d+[^~]*~[0-9a-f]+,agent_version:[^,]+,host_name:"
            + config.getHostName()
            + "$";
    probeStatusSink = new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false);
  }

  @ParameterizedTest(name = "Process tags enabled ''{0}''")
  @ValueSource(booleans = {true, false})
  public void addSnapshot(boolean processTagsEnabled) throws IOException {
    when(config.isExperimentalPropagateProcessTagsEnabled()).thenReturn(processTagsEnabled);
    ProcessTags.reset(config);
    DebuggerSink sink = createDefaultDebuggerSink();
    DebuggerAgentHelper.injectSerializer(new JsonSnapshotSerializer());
    Snapshot snapshot = createSnapshot();
    sink.addSnapshot(snapshot);
    sink.lowRateFlush(sink);
    verify(snapshotUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    JsonSnapshotSerializer.IntakeRequest intakeRequest = assertOneIntakeRequest(strPayload);
    assertEquals("dd_debugger", intakeRequest.getDdsource());
    assertEquals("service-name", intakeRequest.getService());
    assertEquals("java.lang.String", intakeRequest.getLoggerName());
    assertEquals("indexOf", intakeRequest.getLoggerMethod());
    assertEquals(PROBE_ID.getId(), intakeRequest.getDebugger().getSnapshot().getProbe().getId());
    assertEquals(
        PROBE_LOCATION, intakeRequest.getDebugger().getSnapshot().getProbe().getLocation());
    assertTrue(
        intakeRequest
            .getDebugger()
            .getRuntimeId()
            .matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"));
    if (processTagsEnabled) {
      assertNotNull(ProcessTags.getTagsForSerialization());
      assertEquals(
          ProcessTags.getTagsForSerialization().toString(), intakeRequest.getProcessTags());
    } else {
      assertNull(intakeRequest.getProcessTags());
    }
  }

  @Test
  public void addMultipleSnapshots() throws IOException {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(2);
    DebuggerSink sink = createDefaultDebuggerSink();
    DebuggerAgentHelper.injectSerializer(new JsonSnapshotSerializer());
    Snapshot snapshot = createSnapshot();
    Arrays.asList(snapshot, snapshot).forEach(sink::addSnapshot);
    sink.lowRateFlush(sink);
    verify(snapshotUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    ParameterizedType type =
        Types.newParameterizedType(List.class, JsonSnapshotSerializer.IntakeRequest.class);
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter =
        MoshiSnapshotTestHelper.createMoshiSnapshot().adapter(type);
    List<JsonSnapshotSerializer.IntakeRequest> intakeRequests = adapter.fromJson(strPayload);
    assertEquals(2, intakeRequests.size());
  }

  @Test
  public void splitSnapshotBatch() {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(10);
    DebuggerSink sink = createDefaultDebuggerSink();
    DebuggerAgentHelper.injectSerializer(new JsonSnapshotSerializer());
    Snapshot largeSnapshot = createSnapshot();
    for (int i = 0; i < 15_000; i++) {
      largeSnapshot.getStack().add(new CapturedStackFrame("f" + i, i));
    }
    for (int i = 0; i < 10; i++) {
      sink.addSnapshot(largeSnapshot);
    }
    sink.lowRateFlush(sink);
    verify(snapshotUploader, times(2))
        .upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    Assertions.assertTrue(payloadCaptor.getAllValues().get(0).length < MAX_PAYLOAD);
    Assertions.assertTrue(payloadCaptor.getAllValues().get(1).length < MAX_PAYLOAD);
  }

  @Test
  public void tooLargeSnapshot() {
    DebuggerSink sink = createDefaultDebuggerSink();
    Snapshot largeSnapshot = createSnapshot();
    for (int i = 0; i < 150_000; i++) {
      largeSnapshot.getStack().add(new CapturedStackFrame("f" + i, i));
    }
    sink.addSnapshot(largeSnapshot);
    sink.lowRateFlush(sink);
    verifyNoInteractions(snapshotUploader);
  }

  @Test
  public void tooLargeUTF8Snapshot() {
    DebuggerSink sink = createDefaultDebuggerSink();
    Snapshot largeSnapshot = createSnapshot();
    for (int i = 0; i < 140_000; i++) {
      largeSnapshot.getStack().add(new CapturedStackFrame("f€" + i, i));
    }
    sink.addSnapshot(largeSnapshot);
    sink.lowRateFlush(sink);
    verifyNoInteractions(snapshotUploader);
  }

  static class Node {
    String name;
    List<Node> children;

    public Node(String name, List<Node> children) {
      this.name = name;
      this.children = children;
    }
  }

  @Test
  public void pruneTooLargeSnapshot() {
    DebuggerSink sink = createDefaultDebuggerSink();
    char[] chars = new char[Limits.DEFAULT_LENGTH];
    Arrays.fill(chars, 'a');
    String strPayLoad = new String(chars);
    List<Node> children = createChildren(3, strPayLoad);
    Node root = new Node("ROOT", children);
    CapturedValue rootLocal =
        CapturedValue.of(
            "root",
            Node.class.getTypeName(),
            root,
            5,
            Limits.DEFAULT_COLLECTION_SIZE,
            Limits.DEFAULT_LENGTH,
            Limits.DEFAULT_FIELD_COUNT);
    CapturedContext context = new CapturedContext();
    context.addLocals(new CapturedValue[] {rootLocal});
    Snapshot largeSnapshot =
        new Snapshot(
            Thread.currentThread(),
            new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION),
            5);
    largeSnapshot.setEntry(context);
    sink.addSnapshot(largeSnapshot);
    sink.lowRateFlush(sink);
    verify(snapshotUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    assertTrue(strPayload.length() < SnapshotSink.MAX_SNAPSHOT_SIZE);
  }

  private List<Node> createChildren(int level, String strPayLoad) {
    if (level == 0) {
      return Collections.emptyList();
    }
    ArrayList<Node> list = new ArrayList<>();
    for (int i = 0; i < Limits.DEFAULT_COLLECTION_SIZE; i++) {
      list.add(new Node(strPayLoad, createChildren(level - 1, strPayLoad)));
    }
    return list;
  }

  @Test
  public void addNoSnapshots() {
    DebuggerSink sink = createDefaultDebuggerSink();
    sink.lowRateFlush(sink);
    verifyNoInteractions(snapshotUploader);
  }

  @Test
  public void addDiagnostics() throws IOException {
    BatchUploader diagnosticUploader = mock(BatchUploader.class);
    DebuggerSink sink = createDebuggerSink(diagnosticUploader, false);
    sink.addReceived(new ProbeId("1", 2));
    sink.lowRateFlush(sink);
    verify(diagnosticUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    ParameterizedType type = Types.newParameterizedType(List.class, ProbeStatus.class);
    JsonAdapter<List<ProbeStatus>> adapter = MoshiHelper.createMoshiProbeStatus().adapter(type);
    List<ProbeStatus> statuses = adapter.fromJson(strPayload);
    assertEquals(1, statuses.size());
    ProbeStatus status = statuses.get(0);
    assertEquals("dd_debugger", status.getDdSource());
    assertEquals("Received probe ProbeId{id='1', version=2}.", status.getMessage());
    assertEquals("service-name", status.getService());
    assertEquals(ProbeStatus.Status.RECEIVED, status.getDiagnostics().getStatus());
    assertEquals("1", status.getDiagnostics().getProbeId().getId());
  }

  @Test
  public void addDiagnosticsDebuggerTrack() throws IOException {
    BatchUploader diagnosticUploader = mock(BatchUploader.class);
    DebuggerSink sink = createDebuggerSink(diagnosticUploader, true);
    sink.addReceived(new ProbeId("1", 2));
    sink.lowRateFlush(sink);
    ArgumentCaptor<BatchUploader.MultiPartContent> partCaptor =
        ArgumentCaptor.forClass(BatchUploader.MultiPartContent.class);
    verify(diagnosticUploader).uploadAsMultipart(anyString(), partCaptor.capture());
    String strPayload =
        new String(partCaptor.getAllValues().get(0).getContent(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    ParameterizedType type = Types.newParameterizedType(List.class, ProbeStatus.class);
    JsonAdapter<List<ProbeStatus>> adapter = MoshiHelper.createMoshiProbeStatus().adapter(type);
    List<ProbeStatus> statuses = adapter.fromJson(strPayload);
    assertEquals(1, statuses.size());
    ProbeStatus status = statuses.get(0);
    assertEquals("dd_debugger", status.getDdSource());
    assertEquals("Received probe ProbeId{id='1', version=2}.", status.getMessage());
    assertEquals("service-name", status.getService());
    assertEquals(ProbeStatus.Status.RECEIVED, status.getDiagnostics().getStatus());
    assertEquals("1", status.getDiagnostics().getProbeId().getId());
  }

  @Test
  public void addMultipleDiagnostics() throws IOException {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    BatchUploader diagnosticUploader = mock(BatchUploader.class);
    DebuggerSink sink = createDebuggerSink(diagnosticUploader, false);
    for (String probeId : Arrays.asList("1", "2")) {
      sink.addReceived(new ProbeId(probeId, 1));
    }
    sink.lowRateFlush(sink);
    verify(diagnosticUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    ParameterizedType type = Types.newParameterizedType(List.class, ProbeStatus.class);
    JsonAdapter<List<ProbeStatus>> adapter = MoshiHelper.createMoshiProbeStatus().adapter(type);
    List<ProbeStatus> statuses = adapter.fromJson(strPayload);
    assertEquals(2, statuses.size());
  }

  @Test
  public void addMultipleDiagnosticsDebuggerTrack() throws IOException {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    BatchUploader diagnosticUploader = mock(BatchUploader.class);
    DebuggerSink sink = createDebuggerSink(diagnosticUploader, true);
    for (String probeId : Arrays.asList("1", "2")) {
      sink.addReceived(new ProbeId(probeId, 1));
    }
    sink.lowRateFlush(sink);
    ArgumentCaptor<BatchUploader.MultiPartContent> partCaptor =
        ArgumentCaptor.forClass(BatchUploader.MultiPartContent.class);
    verify(diagnosticUploader).uploadAsMultipart(anyString(), partCaptor.capture());
    String strPayload =
        new String(partCaptor.getAllValues().get(0).getContent(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    ParameterizedType type = Types.newParameterizedType(List.class, ProbeStatus.class);
    JsonAdapter<List<ProbeStatus>> adapter = MoshiHelper.createMoshiProbeStatus().adapter(type);
    List<ProbeStatus> statuses = adapter.fromJson(strPayload);
    assertEquals(2, statuses.size());
  }

  @Test
  public void splitDiagnosticsBatch() {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    BatchUploader diagnosticUploader = mock(BatchUploader.class);
    DebuggerSink sink = createDebuggerSink(diagnosticUploader, false);
    StringBuilder largeMessageBuilder = new StringBuilder(100_001);
    for (int i = 0; i < 100_000; i++) {
      largeMessageBuilder.append('f');
    }
    String largeMessage = largeMessageBuilder.toString();
    for (int i = 0; i < 100; i++) {
      sink.getProbeDiagnosticsSink().addError(new ProbeId(String.valueOf(i), i), largeMessage);
    }
    sink.lowRateFlush(sink);
    verify(diagnosticUploader, times(2))
        .upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    Assertions.assertTrue(payloadCaptor.getAllValues().get(0).length < MAX_PAYLOAD);
    Assertions.assertTrue(payloadCaptor.getAllValues().get(1).length < MAX_PAYLOAD);
  }

  @Test
  public void splitDiagnosticsBatchDebuggerTrack() {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    BatchUploader diagnosticUploader = mock(BatchUploader.class);
    DebuggerSink sink = createDebuggerSink(diagnosticUploader, true);
    StringBuilder largeMessageBuilder = new StringBuilder(100_001);
    for (int i = 0; i < 100_000; i++) {
      largeMessageBuilder.append('f');
    }
    String largeMessage = largeMessageBuilder.toString();
    for (int i = 0; i < 100; i++) {
      sink.getProbeDiagnosticsSink().addError(new ProbeId(String.valueOf(i), i), largeMessage);
    }
    sink.lowRateFlush(sink);
    ArgumentCaptor<BatchUploader.MultiPartContent> partCaptor =
        ArgumentCaptor.forClass(BatchUploader.MultiPartContent.class);
    verify(diagnosticUploader, times(2)).uploadAsMultipart(anyString(), partCaptor.capture());
    Assertions.assertTrue(partCaptor.getAllValues().get(0).getContent().length < MAX_PAYLOAD);
    Assertions.assertTrue(partCaptor.getAllValues().get(1).getContent().length < MAX_PAYLOAD);
  }

  @Test
  public void tooLargeDiagnostic() {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    DebuggerSink sink = createDefaultDebuggerSink();
    StringBuilder tooLargeMessageBuilder = new StringBuilder(MAX_PAYLOAD + 1);
    for (int i = 0; i < MAX_PAYLOAD; i++) {
      tooLargeMessageBuilder.append('f');
    }
    String tooLargeMessage = tooLargeMessageBuilder.toString();
    sink.getProbeDiagnosticsSink().addError(new ProbeId("1", 1), tooLargeMessage);
    sink.lowRateFlush(sink);
    verifyNoInteractions(snapshotUploader);
  }

  @Test
  public void tooLargeUTF8Diagnostic() {
    when(config.getDynamicInstrumentationUploadBatchSize()).thenReturn(100);
    DebuggerSink sink = createDefaultDebuggerSink();
    StringBuilder tooLargeMessageBuilder = new StringBuilder(MAX_PAYLOAD + 4);
    for (int i = 0; i < MAX_PAYLOAD; i += 4) {
      tooLargeMessageBuilder.append("\uD80C\uDCF0"); // 4 bytes
    }
    String tooLargeMessage = tooLargeMessageBuilder.toString();
    sink.getProbeDiagnosticsSink().addError(new ProbeId("1", 1), tooLargeMessage);
    sink.lowRateFlush(sink);
    verifyNoInteractions(snapshotUploader);
  }

  @Test
  public void addNoDiagnostic() {
    DebuggerSink sink = createDefaultDebuggerSink();
    sink.lowRateFlush(sink);
    verifyNoInteractions(snapshotUploader);
  }

  @Test
  public void reconsiderFlushIntervalIncreaseFlushInterval() {
    DebuggerSink sink = createDefaultDebuggerSink();
    long currentFlushInterval = sink.getCurrentLowRateFlushInterval();
    Snapshot snapshot = createSnapshot();
    sink.addSnapshot(snapshot);
    sink.doReconsiderLowRateFlushInterval();
    long newFlushInterval = sink.getCurrentLowRateFlushInterval();
    assertEquals(currentFlushInterval + DebuggerSink.LOW_RATE_STEP_SIZE, newFlushInterval);
  }

  @Test
  public void reconsiderFlushIntervalDecreaseFlushInterval() {
    DebuggerSink sink = createDefaultDebuggerSink();
    long currentFlushInterval = sink.getCurrentLowRateFlushInterval();
    sink.lowRateFlush(sink);
    Snapshot snapshot = createSnapshot();
    for (int i = 0; i < 1000; i++) {
      sink.addSnapshot(snapshot);
    }
    sink.doReconsiderLowRateFlushInterval();
    long newFlushInterval = sink.getCurrentLowRateFlushInterval();
    assertEquals(currentFlushInterval - DebuggerSink.LOW_RATE_STEP_SIZE, newFlushInterval);
  }

  @Test
  public void reconsiderFlushIntervalNoChange() {
    DebuggerSink sink = createDefaultDebuggerSink();
    long currentFlushInterval = sink.getCurrentLowRateFlushInterval();
    Snapshot snapshot = createSnapshot();
    for (int i = 0; i < 500; i++) {
      sink.addSnapshot(snapshot);
    }
    sink.doReconsiderLowRateFlushInterval();
    long newFlushInterval = sink.getCurrentLowRateFlushInterval();
    assertEquals(currentFlushInterval, newFlushInterval);
  }

  @Test
  public void addSnapshotWithCorrelationIdsMethodProbe() throws IOException {
    DebuggerSink sink = createDefaultDebuggerSink();
    DebuggerAgentHelper.injectSerializer(new JsonSnapshotSerializer());
    Snapshot snapshot = createSnapshot();
    snapshot.setTraceId("123");
    snapshot.setSpanId("456");
    sink.addSnapshot(snapshot);
    sink.lowRateFlush(sink);
    verify(snapshotUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    JsonSnapshotSerializer.IntakeRequest intakeRequest = assertOneIntakeRequest(strPayload);
    assertEquals("123", intakeRequest.getTraceId());
    assertEquals("456", intakeRequest.getSpanId());
  }

  @Test
  public void addSnapshotWithEvalErrors() throws IOException {
    DebuggerSink sink = createDefaultDebuggerSink();
    DebuggerAgentHelper.injectSerializer(new JsonSnapshotSerializer());
    CapturedContext entry = new CapturedContext();
    Snapshot snapshot = createSnapshot();
    snapshot.setEntry(entry);
    snapshot.addEvaluationErrors(
        Arrays.asList(new EvaluationError("obj.field", "Cannot dereference obj")));
    sink.addSnapshot(snapshot);
    sink.lowRateFlush(sink);
    verify(snapshotUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
    String strPayload = new String(payloadCaptor.getValue(), StandardCharsets.UTF_8);
    System.out.println(strPayload);
    JsonSnapshotSerializer.IntakeRequest intakeRequest = assertOneIntakeRequest(strPayload);
    List<EvaluationError> evaluationErrors =
        intakeRequest.getDebugger().getSnapshot().getEvaluationErrors();
    assertEquals(1, evaluationErrors.size());
    assertEquals("obj.field", evaluationErrors.get(0).getExpr());
    assertEquals("Cannot dereference obj", evaluationErrors.get(0).getMessage());
  }

  @Test
  public void addDiagnostic() {
    DebuggerSink sink = createDefaultDebuggerSink();
    DiagnosticMessage info = new DiagnosticMessage(DiagnosticMessage.Kind.INFO, "info message");
    DiagnosticMessage warn = new DiagnosticMessage(DiagnosticMessage.Kind.WARN, "info message");
    DiagnosticMessage error = new DiagnosticMessage(DiagnosticMessage.Kind.ERROR, "info message");
    sink.addDiagnostics(PROBE_ID, Arrays.asList(info, warn, error));
    // ensure just that the code is executed to have coverage (just logging)
  }

  @Test
  public void skipSnapshot() {
    DebuggerMetrics debuggerMetrics = spy(DebuggerMetrics.getInstance(config));
    SnapshotSink snapshotSink =
        new SnapshotSink(
            config,
            "",
            new BatchUploader(
                "Snapshots",
                config,
                config.getFinalDebuggerSnapshotUrl(),
                SnapshotSink.RETRY_POLICY),
            new BatchUploader(
                "Logs", config, config.getFinalDebuggerSnapshotUrl(), SnapshotSink.RETRY_POLICY));
    SymbolSink symbolSink = new SymbolSink(config);
    DebuggerSink sink =
        new DebuggerSink(config, "", debuggerMetrics, probeStatusSink, snapshotSink, symbolSink);
    Snapshot snapshot = createSnapshot();
    sink.skipSnapshot(snapshot.getProbe().getId(), DebuggerContext.SkipCause.CONDITION);
    verify(debuggerMetrics)
        .incrementCounter(anyString(), eq("cause:condition"), eq("probe_id:" + PROBE_ID.getId()));
    sink.skipSnapshot(snapshot.getProbe().getId(), DebuggerContext.SkipCause.RATE);
    verify(debuggerMetrics)
        .incrementCounter(anyString(), eq("cause:rate"), eq("probe_id:" + PROBE_ID.getId()));
  }

  private JsonSnapshotSerializer.IntakeRequest assertOneIntakeRequest(String strPayload)
      throws IOException {
    ParameterizedType type =
        Types.newParameterizedType(List.class, JsonSnapshotSerializer.IntakeRequest.class);
    JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter =
        MoshiSnapshotTestHelper.createMoshiSnapshot().adapter(type);
    List<JsonSnapshotSerializer.IntakeRequest> intakeRequests = adapter.fromJson(strPayload);
    assertEquals(1, intakeRequests.size());
    return intakeRequests.get(0);
  }

  private Snapshot createSnapshot() {
    return new Snapshot(
        Thread.currentThread(),
        new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION),
        Limits.DEFAULT_REFERENCE_DEPTH);
  }

  private DebuggerSink createDefaultDebuggerSink() {
    String tags = DebuggerAgent.getDefaultTagsMergedWithGlobalTags(config);
    return new DebuggerSink(
        config,
        tags,
        DebuggerMetrics.getInstance(config),
        probeStatusSink,
        new SnapshotSink(config, tags, snapshotUploader, logUploader),
        new SymbolSink(config));
  }

  private DebuggerSink createDebuggerSink(BatchUploader diagnosticUploader, boolean useMultiPart) {
    String tags = DebuggerAgent.getDefaultTagsMergedWithGlobalTags(config);
    ProbeStatusSink probeSink = new ProbeStatusSink(config, diagnosticUploader, useMultiPart);
    return new DebuggerSink(
        config,
        tags,
        DebuggerMetrics.getInstance(config),
        probeSink,
        new SnapshotSink(config, tags, snapshotUploader, logUploader),
        new SymbolSink(config));
  }
}
