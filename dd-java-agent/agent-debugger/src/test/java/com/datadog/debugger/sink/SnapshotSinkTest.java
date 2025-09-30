package com.datadog.debugger.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.DebuggerAgent;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.uploader.BatchUploader;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Limits;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.bootstrap.debugger.ProbeImplementation;
import datadog.trace.bootstrap.debugger.ProbeLocation;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
public class SnapshotSinkTest {
  private static final ProbeId PROBE_ID = new ProbeId("12fd-8490-c111-4374-ffde", 42);
  private static final ProbeLocation PROBE_LOCATION =
      new ProbeLocation("java.lang.String", "indexOf", null, null);

  @Mock private Config config;
  @Mock private BatchUploader snapshotUploader;
  @Mock private BatchUploader logUploader;
  @Captor private ArgumentCaptor<byte[]> payloadCaptor;
  private ProbeStatusSink probeStatusSink;
  private String EXPECTED_SNAPSHOT_TAGS;

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

    EXPECTED_SNAPSHOT_TAGS =
        "^env:test,version:foo,debugger_version:\\d+\\.\\d+\\.\\d+[^~]*~[0-9a-f]+,agent_version:null,host_name:"
            + config.getHostName()
            + "$";
    probeStatusSink = new ProbeStatusSink(config, config.getFinalDebuggerSnapshotUrl(), false);
  }

  @ParameterizedTest(name = "Process tags enabled ''{0}''")
  @ValueSource(booleans = {true, false})
  public void addHighRateSnapshot(boolean processTagsEnabled) throws IOException {
    when(config.isExperimentalPropagateProcessTagsEnabled()).thenReturn(processTagsEnabled);
    ProcessTags.reset(config);
    SnapshotSink snapshotSink = createSnapshotSink();
    snapshotSink.start();
    Snapshot snapshot = createSnapshot();
    snapshotSink.addHighRate(snapshot);
    snapshotSink.highRateFlush(null);
    verify(logUploader).upload(payloadCaptor.capture(), matches(EXPECTED_SNAPSHOT_TAGS));
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
  public void reconsiderDecreaseFlushInterval() {
    SnapshotSink snapshotSink = createSnapshotSink();
    long previousInterval = snapshotSink.getCurrentHighRateFlushInterval();
    assertEquals(SnapshotSink.HIGH_RATE_MAX_FLUSH_INTERVAL_MS, previousInterval);
    for (int i = 0; i < 1000; i++) {
      snapshotSink.addHighRate(createSnapshot());
    }
    snapshotSink.highRateFlush(null); // interval / 4
    long currentInterval = snapshotSink.getCurrentHighRateFlushInterval();
    assertEquals(previousInterval / 4, currentInterval);
    previousInterval = currentInterval;
    for (int i = 0; i < 520; i++) {
      snapshotSink.addHighRate(createSnapshot());
    }
    snapshotSink.highRateFlush(null); // interval / 2
    currentInterval = snapshotSink.getCurrentHighRateFlushInterval();
    assertEquals(previousInterval / 2, currentInterval);
    previousInterval = currentInterval;
    for (int i = 0; i < 110; i++) {
      snapshotSink.addHighRate(createSnapshot());
    }
    snapshotSink.highRateFlush(null); // interval - HIGH_RATE_STEP_SIZE
    currentInterval = snapshotSink.getCurrentHighRateFlushInterval();
    assertEquals(previousInterval - SnapshotSink.HIGH_RATE_STEP_SIZE, currentInterval);
    previousInterval = currentInterval;
    for (int i = 0; i < 1000; i++) {
      snapshotSink.addHighRate(createSnapshot());
    }
    snapshotSink.highRateFlush(null); // interval / 4 => min
    currentInterval = snapshotSink.getCurrentHighRateFlushInterval();
    assertEquals(SnapshotSink.HIGH_RATE_MIN_FLUSH_INTERVAL_MS, currentInterval);
  }

  @Test
  public void backoffFlushInterval() {
    SnapshotSink snapshotSink = createSnapshotSink();
    long previousInterval = snapshotSink.getCurrentHighRateFlushInterval();
    assertEquals(SnapshotSink.HIGH_RATE_MAX_FLUSH_INTERVAL_MS, previousInterval);
    for (int flushCount = 0; flushCount < 4; flushCount++) {
      for (int i = 0; i < 1000; i++) {
        snapshotSink.addHighRate(createSnapshot());
      }
      snapshotSink.highRateFlush(null); // interval / 4
      previousInterval = snapshotSink.getCurrentHighRateFlushInterval();
    }
    assertEquals(SnapshotSink.HIGH_RATE_MIN_FLUSH_INTERVAL_MS, previousInterval);
    previousInterval = snapshotSink.getCurrentHighRateFlushInterval();
    snapshotSink.highRateFlush(null); // backoff: interval + HIGH_RATE_STEP_SIZE
    long currentInterval = snapshotSink.getCurrentHighRateFlushInterval();
    assertEquals(previousInterval + SnapshotSink.HIGH_RATE_STEP_SIZE, currentInterval);
  }

  @Test
  public void differentiateSnapshotLog() {
    SnapshotSink snapshotSink = createSnapshotSink();
    Snapshot snapshot = createSnapshot();
    snapshotSink.addLowRate(snapshot);
    snapshotSink.lowRateFlush(DebuggerAgent.getDefaultTagsMergedWithGlobalTags(config));
    Snapshot logSnapshot = createSnapshot();
    snapshotSink.addHighRate(logSnapshot);
    snapshotSink.highRateFlush(null);
    verify(snapshotUploader).upload(any(), matches(EXPECTED_SNAPSHOT_TAGS));
    verify(logUploader).upload(any(), matches(EXPECTED_SNAPSHOT_TAGS));
  }

  private SnapshotSink createSnapshotSink() {
    String tags = DebuggerAgent.getDefaultTagsMergedWithGlobalTags(config);
    return new SnapshotSink(config, tags, snapshotUploader, logUploader);
  }

  private Snapshot createSnapshot() {
    return new Snapshot(
        Thread.currentThread(),
        new ProbeImplementation.NoopProbeImplementation(PROBE_ID, PROBE_LOCATION),
        Limits.DEFAULT_REFERENCE_DEPTH);
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
}
