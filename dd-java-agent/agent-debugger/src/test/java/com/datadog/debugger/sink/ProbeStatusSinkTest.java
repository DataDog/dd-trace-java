package com.datadog.debugger.sink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.agent.ProbeStatus.Builder;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import datadog.trace.api.Config;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProbeStatusSinkTest {

  private static final String SERVICE_NAME = "service-name";
  private static final String PROBE_ID = UUID.randomUUID().toString();
  private static final String MESSAGE = "Foo";
  private static final int DIAGNOSTICS_INTERVAL = 60 * 60; // in seconds = 1h
  private static final Instant AFTER_INTERVAL_HAS_PASSED =
      Instant.now().plus(Duration.ofSeconds(DIAGNOSTICS_INTERVAL + 5));
  private static final Instant BEFORE_INTERVAL_HAS_PASSED =
      Instant.now().plus(Duration.ofSeconds(DIAGNOSTICS_INTERVAL - 5));

  @Mock private Config config;

  private Builder builder;
  private ProbeStatusSink probeStatusSink;

  @BeforeEach
  void setUp() {
    when(config.getServiceName()).thenReturn(SERVICE_NAME);
    when(config.getDebuggerDiagnosticsInterval()).thenReturn(DIAGNOSTICS_INTERVAL);
    when(config.getDebuggerUploadBatchSize()).thenReturn(1000);
    builder = new Builder(config);
    probeStatusSink = new ProbeStatusSink(config);
  }

  @Test
  void addReceived() {
    probeStatusSink.addReceived(PROBE_ID);
    assertEquals(
        Collections.singletonList(builder.receivedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addInstalled() {
    probeStatusSink.addInstalled(PROBE_ID);
    assertEquals(
        Collections.singletonList(builder.installedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addBlocked() {
    probeStatusSink.addBlocked(PROBE_ID);
    assertEquals(
        Collections.singletonList(builder.blockedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addError() {
    probeStatusSink.addError(PROBE_ID, MESSAGE);
    assertEquals(
        Collections.singletonList(builder.errorMessage(PROBE_ID, MESSAGE)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addReceivedThenInstalled() {
    probeStatusSink.addReceived(PROBE_ID);
    probeStatusSink.addInstalled(PROBE_ID);
    assertEquals(
        Arrays.asList(builder.receivedMessage(PROBE_ID), builder.installedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addReceivedThenBlocked() {
    probeStatusSink.addReceived(PROBE_ID);
    probeStatusSink.addBlocked(PROBE_ID);
    assertEquals(
        Arrays.asList(builder.receivedMessage(PROBE_ID), builder.blockedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addReceivedThenError() {
    probeStatusSink.addReceived(PROBE_ID);
    probeStatusSink.addError(PROBE_ID, MESSAGE);
    assertEquals(
        Arrays.asList(builder.receivedMessage(PROBE_ID), builder.errorMessage(PROBE_ID, MESSAGE)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addReceivedThenInstalledThenError() {
    probeStatusSink.addReceived(PROBE_ID);
    probeStatusSink.addInstalled(PROBE_ID);
    probeStatusSink.addError(PROBE_ID, MESSAGE);
    assertEquals(
        Arrays.asList(
            builder.receivedMessage(PROBE_ID),
            builder.installedMessage(PROBE_ID),
            builder.errorMessage(PROBE_ID, MESSAGE)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void addErrorWithThrowable() {
    Throwable throwable = new Exception("test");
    probeStatusSink.addError(PROBE_ID, throwable);
    assertEquals(
        Collections.singletonList(builder.errorMessage(PROBE_ID, throwable)),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void removeDiagnostic() {
    probeStatusSink.addReceived(PROBE_ID);
    assertEquals(
        Collections.singletonList(builder.receivedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
    probeStatusSink.removeDiagnostics(PROBE_ID);
    assertEquals(Collections.emptyList(), probeStatusSink.getDiagnostics());
  }

  @Test
  void doNotDoubleEmitMessageIfIntervalHasntPassed() {
    probeStatusSink.addError(PROBE_ID, MESSAGE);
    assertEquals(
        Collections.singletonList(builder.errorMessage(PROBE_ID, MESSAGE)),
        probeStatusSink.getDiagnostics());
    assertEquals(Collections.emptyList(), probeStatusSink.getDiagnostics());
  }

  @Test
  void reemitOnInterval() {
    probeStatusSink.addReceived(PROBE_ID);
    List<ProbeStatus> expected = Collections.singletonList(builder.receivedMessage(PROBE_ID));
    assertEquals(expected, probeStatusSink.getDiagnostics());
    assertEquals(Collections.emptyList(), probeStatusSink.getDiagnostics());
    Clock fixed = Clock.fixed(AFTER_INTERVAL_HAS_PASSED, ZoneId.systemDefault());
    assertEquals(expected, probeStatusSink.getDiagnostics(fixed));
  }

  @Test
  void reemitOnlyLatestMessage() {
    probeStatusSink.addReceived(PROBE_ID);
    probeStatusSink.addError(PROBE_ID, MESSAGE);
    assertEquals(
        Arrays.asList(builder.receivedMessage(PROBE_ID), builder.errorMessage(PROBE_ID, MESSAGE)),
        probeStatusSink.getDiagnostics());
    Clock fixed = Clock.fixed(AFTER_INTERVAL_HAS_PASSED, ZoneId.systemDefault());
    assertEquals(
        Collections.singletonList(builder.errorMessage(PROBE_ID, MESSAGE)),
        probeStatusSink.getDiagnostics(fixed));
  }

  @Test
  void doNotReemitIfIntervalHasNotPassedBetweenAddingAndNextCall() {
    probeStatusSink.addReceived(PROBE_ID);
    assertEquals(
        Collections.singletonList(builder.receivedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
    Clock fixed = Clock.fixed(BEFORE_INTERVAL_HAS_PASSED, ZoneId.systemDefault());
    assertEquals(Collections.emptyList(), probeStatusSink.getDiagnostics(fixed));
  }

  @Test
  void doNotReemitIfIntervalHasNotPassedBetweenAddingAndNextCallNewStatus() {
    probeStatusSink.addReceived(PROBE_ID);
    assertEquals(
        Collections.singletonList(builder.receivedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
    probeStatusSink.addInstalled(PROBE_ID);
    Clock fixed = Clock.fixed(BEFORE_INTERVAL_HAS_PASSED, ZoneId.systemDefault());
    assertEquals(
        Collections.singletonList(builder.installedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics(fixed));
  }

  @Test
  void multipleProbes() {
    String secondProbeId = UUID.randomUUID().toString();

    // Emit both right-away after adding the messages
    probeStatusSink.addReceived(PROBE_ID);
    probeStatusSink.addReceived(secondProbeId);
    assertEquals(
        Arrays.asList(builder.receivedMessage(PROBE_ID), builder.receivedMessage(secondProbeId)),
        probeStatusSink.getDiagnostics());

    // Change stored diagnostic for PROBE_ID
    probeStatusSink.addInstalled(PROBE_ID);

    // Assert only new (installed) message for PROBE_ID is emitted before DIAGNOSTICS_INTERVAL has
    // passed
    Instant beforeIntervalHasPassed = Instant.now();
    Clock fixed = Clock.fixed(beforeIntervalHasPassed, ZoneId.systemDefault());
    assertEquals(
        Collections.singletonList(builder.installedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics(fixed));
  }

  @Test
  void doNotReemitRemovedMessage() {
    probeStatusSink.addReceived(PROBE_ID);
    assertEquals(
        Collections.singletonList(builder.receivedMessage(PROBE_ID)),
        probeStatusSink.getDiagnostics());
    probeStatusSink.removeDiagnostics(PROBE_ID);
    assertEquals(Collections.emptyList(), probeStatusSink.getDiagnostics());
  }

  @Test
  void dropDiagnostics() {
    probeStatusSink.addReceived(PROBE_ID);
    for (int i = 1; i <= 999; i++) {
      probeStatusSink.addError(PROBE_ID, "foo");
    }
    // This will trigger clearing and re-populating the queue with the latest status per probe
    probeStatusSink.addError(PROBE_ID, "bar");
    assertEquals(
        Collections.singletonList(builder.errorMessage(PROBE_ID, "bar")),
        probeStatusSink.getDiagnostics());
  }

  @Test
  void roundtripSerialization() throws IOException {
    String buffer = serialize();
    deserialize(buffer);
  }

  private String serialize() {
    ProbeStatus probeStatus = builder.errorMessage(PROBE_ID, new NullPointerException("null"));
    JsonAdapter<ProbeStatus> adapter =
        MoshiHelper.createMoshiProbeStatus().adapter(ProbeStatus.class);
    return adapter.toJson(probeStatus);
  }

  private void deserialize(String buffer) throws IOException {
    JsonAdapter<ProbeStatus> adapter =
        MoshiHelper.createMoshiProbeStatus().adapter(ProbeStatus.class);
    ProbeStatus probeStatus = adapter.fromJson(buffer);
    assertEquals("dd_debugger", probeStatus.getDdSource());
    assertEquals(SERVICE_NAME, probeStatus.getService());
    assertEquals("Error installing probe " + PROBE_ID + ".", probeStatus.getMessage());
    assertEquals(PROBE_ID, probeStatus.getDiagnostics().getProbeId());
    assertEquals(ProbeStatus.Status.ERROR, probeStatus.getDiagnostics().getStatus());
    assertEquals("null", probeStatus.getDiagnostics().getException().getMessage());
    assertEquals(
        "ProbeStatusSinkTest.java",
        probeStatus.getDiagnostics().getException().getStacktrace().get(0).getFileName());
    assertEquals(
        "com.datadog.debugger.sink.ProbeStatusSinkTest.serialize",
        probeStatus.getDiagnostics().getException().getStacktrace().get(0).getFunction());
  }
}
