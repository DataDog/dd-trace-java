package com.datadog.debugger.agent;

import static com.datadog.debugger.probe.ProbeDefinitionSerializer.serializeLogProbe;
import static com.datadog.debugger.probe.ProbeDefinitionSerializer.serializeMetricProbe;
import static com.datadog.debugger.probe.ProbeDefinitionSerializer.serializeSpanDecorationProbe;
import static com.datadog.debugger.probe.ProbeDefinitionSerializer.serializeSpanProbe;
import static com.datadog.debugger.probe.ProbeDefinitionSerializer.serializeTriggerProbe;
import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static datadog.remoteconfig.PollingHinterNoop.NOOP;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DebuggerProductChangesListenerTest {
  private static final String SERVICE_NAME = "service-name";

  @Mock private Config tracerConfig;

  static class SimpleAcceptor implements ConfigurationAcceptor {
    private Collection<? extends ProbeDefinition> definitions;
    private Exception lastException;

    @Override
    public void accept(Source source, Collection<? extends ProbeDefinition> definitions) {
      this.definitions = definitions;
    }

    @Override
    public void handleException(String configId, Exception ex) {
      lastException = ex;
    }

    public Collection<? extends ProbeDefinition> getDefinitions() {
      return definitions;
    }

    public Exception getLastException() {
      return lastException;
    }
  }

  @BeforeEach
  void setUp() {
    lenient().when(tracerConfig.getServiceName()).thenReturn(SERVICE_NAME);
  }

  @Test
  public void testNoConfiguration() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);
    listener.commit(NOOP);

    assertTrue(acceptor.getDefinitions().isEmpty());
  }

  @Test
  public void testMultipleSingleProbesConfigurations() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);

    MetricProbe metricProbe = createMetricProbe(UUID.randomUUID().toString());
    LogProbe logProbe = createLogProbe(UUID.randomUUID().toString());
    SpanProbe spanProbe = createSpanProbe(UUID.randomUUID().toString());
    SpanDecorationProbe spanDecorationProbe =
        createSpanDecorationProbe(UUID.randomUUID().toString());
    TriggerProbe triggerProbe = createTriggerProbe(UUID.randomUUID().toString());

    acceptMetricProbe(listener, metricProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), metricProbe);

    acceptLogProbe(listener, logProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), metricProbe, logProbe);

    acceptSpanProbe(listener, spanProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), metricProbe, logProbe, spanProbe);

    acceptSpanDecorationProbe(listener, spanDecorationProbe);
    listener.commit(NOOP);
    assertDefinitions(
        acceptor.getDefinitions(), metricProbe, logProbe, spanProbe, spanDecorationProbe);

    acceptTriggerProbe(listener, triggerProbe);
    listener.commit(NOOP);
    assertDefinitions(
        acceptor.getDefinitions(),
        metricProbe,
        logProbe,
        spanProbe,
        spanDecorationProbe,
        triggerProbe);

    removeMetricProbe(listener, metricProbe);
    listener.commit(NOOP);
    assertDefinitions(
        acceptor.getDefinitions(), logProbe, spanProbe, spanDecorationProbe, triggerProbe);

    removeLogProbe(listener, logProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), spanProbe, spanDecorationProbe, triggerProbe);

    removeSpanProbe(listener, spanProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), spanDecorationProbe, triggerProbe);

    removeSpanDecorationProbe(listener, spanDecorationProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), triggerProbe);

    removeTriggerProbe(listener, triggerProbe);
    listener.commit(NOOP);
    assertTrue(acceptor.getDefinitions().isEmpty());
  }

  @Test
  public void badConfigIDFailsToAccept() throws IOException {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);
    listener.accept(createConfigKey("bad-config-id"), null, NOOP);
    assertNull(acceptor.definitions);
  }

  @Test
  public void parsingException() throws IOException {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener = new DebuggerProductChangesListener(acceptor);
    String probeUUID = UUID.randomUUID().toString();
    IOException ioException =
        assertThrows(
            IOException.class,
            () ->
                listener.accept(
                    createConfigKey("logProbe_" + probeUUID),
                    "{bad json}".getBytes(StandardCharsets.UTF_8),
                    NOOP));
    assertNotNull(acceptor.lastException);
    assertEquals(ioException.getCause(), acceptor.lastException);
  }

  private void assertDefinitions(
      Collection<? extends ProbeDefinition> actualDefinitions,
      ProbeDefinition... expectedDefinitions) {
    assertEquals(
        new HashSet<>(Arrays.asList(expectedDefinitions)), new HashSet<>(actualDefinitions));
  }

  byte[] toContent(MetricProbe probe) {
    return serializeMetricProbe(probe).getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(LogProbe probe) {
    return serializeLogProbe(probe).getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(SpanProbe probe) {
    return serializeSpanProbe(probe).getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(SpanDecorationProbe probe) {
    return serializeSpanDecorationProbe(probe).getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(TriggerProbe probe) {
    return serializeTriggerProbe(probe).getBytes(StandardCharsets.UTF_8);
  }

  void acceptMetricProbe(DebuggerProductChangesListener listener, MetricProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("metricProbe_" + probe.getId()), toContent(probe), NOOP));
  }

  void removeMetricProbe(DebuggerProductChangesListener listener, MetricProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("metricProbe_" + probe.getId()), NOOP));
  }

  void acceptLogProbe(DebuggerProductChangesListener listener, LogProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(createConfigKey("logProbe_" + probe.getId()), toContent(probe), NOOP));
  }

  void removeLogProbe(DebuggerProductChangesListener listener, LogProbe probe) {
    assertDoesNotThrow(() -> listener.remove(createConfigKey("logProbe_" + probe.getId()), NOOP));
  }

  void acceptSpanProbe(DebuggerProductChangesListener listener, SpanProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(createConfigKey("spanProbe_" + probe.getId()), toContent(probe), NOOP));
  }

  void acceptSpanDecorationProbe(
      DebuggerProductChangesListener listener, SpanDecorationProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("spanDecorationProbe_" + probe.getId()), toContent(probe), NOOP));
  }

  void acceptTriggerProbe(DebuggerProductChangesListener listener, TriggerProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("triggerProbe_" + probe.getId()), toContent(probe), NOOP));
  }

  void removeSpanProbe(DebuggerProductChangesListener listener, SpanProbe probe) {
    assertDoesNotThrow(() -> listener.remove(createConfigKey("spanProbe_" + probe.getId()), NOOP));
  }

  void removeSpanDecorationProbe(
      DebuggerProductChangesListener listener, SpanDecorationProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("spanDecorationProbe_" + probe.getId()), NOOP));
  }

  void removeTriggerProbe(DebuggerProductChangesListener listener, TriggerProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("triggerProbe_" + probe.getId()), NOOP));
  }

  LogProbe createLogProbeWithSnapshot(String id) {
    return LogProbe.builder()
        .probeId(id, 0)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .captureSnapshot(true)
        .build();
  }

  MetricProbe createMetricProbe(String id) {
    return MetricProbe.builder()
        .probeId(id, 0)
        .kind(MetricProbe.MetricKind.COUNT)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .build();
  }

  LogProbe createLogProbe(String id) {
    final String LOG_LINE = "hello {world}";
    return LogProbe.builder()
        .probeId(id, 0)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .template(LOG_LINE, parseTemplate(LOG_LINE))
        .build();
  }

  SpanProbe createSpanProbe(String id) {
    return SpanProbe.builder()
        .probeId(id, 0)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .build();
  }

  SpanDecorationProbe createSpanDecorationProbe(String id) {
    return SpanDecorationProbe.builder()
        .probeId(id, 0)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .targetSpan(SpanDecorationProbe.TargetSpan.ACTIVE)
        .build();
  }

  TriggerProbe createTriggerProbe(String id) {
    return TriggerProbe.builder()
        .probeId(new ProbeId(id, 0))
        .where("java.lang.String", "indexOf", null)
        .build();
  }

  Configuration.FilterList createFilteredList() {
    return new Configuration.FilterList(
        Collections.singletonList("datadog"), Collections.singletonList("class1"));
  }

  ParsedConfigKey createConfigKey(String configId) {
    return ParsedConfigKey.parse("datadog/2/LIVE_DEBUGGING/" + configId + "/config");
  }
}
