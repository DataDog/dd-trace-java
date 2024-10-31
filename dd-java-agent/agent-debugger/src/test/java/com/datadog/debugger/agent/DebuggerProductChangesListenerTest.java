package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.DebuggerProductChangesListener.MAX_ALLOWED_LOG_PROBES;
import static com.datadog.debugger.agent.DebuggerProductChangesListener.MAX_ALLOWED_METRIC_PROBES;
import static com.datadog.debugger.agent.DebuggerProductChangesListener.MAX_ALLOWED_SPAN_DECORATION_PROBES;
import static com.datadog.debugger.agent.DebuggerProductChangesListener.MAX_ALLOWED_SPAN_PROBES;
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
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.trace.api.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DebuggerProductChangesListenerTest {
  private static final String SERVICE_NAME = "service-name";

  @Mock private Config tracerConfig;

  class SimpleAcceptor implements ConfigurationAcceptor {
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

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    listener.commit(NOOP);

    assertTrue(acceptor.getDefinitions().isEmpty());
  }

  @Test
  public void testSingleConfiguration() {
    Configuration config =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(createLogProbeWithSnapshot(UUID.randomUUID().toString()))
            .addDenyList(createFilteredList())
            .build();
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);

    acceptConfig(listener, config, UUID.randomUUID().toString());
    listener.commit(NOOP);

    assertEquals(config.getDefinitions(), acceptor.getDefinitions());
  }

  @Test
  public void rejectConfigurationsFromOtherServices() {
    Configuration config =
        Configuration.builder()
            .setService("other-service")
            .add(createLogProbeWithSnapshot(UUID.randomUUID().toString()))
            .addDenyList(createFilteredList())
            .build();
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);

    Assertions.assertThrows(
        IOException.class,
        () ->
            listener.accept(
                createConfigKey(UUID.randomUUID().toString()), toContent(config), NOOP));

    listener.commit(NOOP);

    assertTrue(acceptor.getDefinitions().isEmpty());
  }

  @Test
  public void testMultipleSingleProbesConfigurations() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);

    MetricProbe metricProbe = createMetricProbe(UUID.randomUUID().toString());
    LogProbe logProbe = createLogProbe(UUID.randomUUID().toString());
    SpanProbe spanProbe = createSpanProbe(UUID.randomUUID().toString());

    acceptMetricProbe(listener, metricProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), metricProbe);

    acceptLogProbe(listener, logProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), metricProbe, logProbe);

    acceptSpanProbe(listener, spanProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), metricProbe, logProbe, spanProbe);

    removeMetricProbe(listener, metricProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), logProbe, spanProbe);

    removeLogProbe(listener, logProbe);
    listener.commit(NOOP);
    assertDefinitions(acceptor.getDefinitions(), spanProbe);

    removeSpanProbe(listener, spanProbe);
    listener.commit(NOOP);
    assertTrue(acceptor.getDefinitions().isEmpty());
  }

  @Test
  public void testMergeConfigWithSingleProbe() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);

    LogProbe logProbeWithSnapshot = createLogProbeWithSnapshot("123");
    MetricProbe metricProbe = createMetricProbe("345");
    LogProbe logProbe = createLogProbe("567");
    SpanProbe spanProbe = createSpanProbe("890");

    Configuration config =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(metricProbe)
            .add(logProbe)
            .add(spanProbe)
            .add(new LogProbe.Sampling(3.0))
            .addDenyList(createFilteredList())
            .build();

    acceptLogProbe(listener, logProbeWithSnapshot);
    acceptConfig(listener, config, UUID.randomUUID().toString());
    listener.commit(NOOP);
    assertDefinitions(
        acceptor.getDefinitions(), logProbeWithSnapshot, metricProbe, logProbe, spanProbe);
  }

  @Test
  public void badConfigIDFailsToAccept() throws IOException {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    listener.accept(createConfigKey("bad-config-id"), null, NOOP);
    assertNull(acceptor.definitions);
  }

  @Test
  public void maxLogProbes() {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    for (int i = 0; i < 200; i++) {
      LogProbe probe =
          LogProbe.builder()
              .probeId(String.valueOf(i), 0)
              .where("java.lang.String", "concat" + i)
              .build();
      acceptLogProbe(listener, probe);
    }
    listener.commit(NOOP);
    assertEquals(MAX_ALLOWED_LOG_PROBES, acceptor.getDefinitions().size());
  }

  @Test
  public void maxMetricProbes() {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    for (int i = 0; i < 200; i++) {
      MetricProbe probe =
          MetricProbe.builder()
              .probeId(String.valueOf(i), 0)
              .where("java.lang.String", "concat" + i)
              .build();
      acceptMetricProbe(listener, probe);
    }
    listener.commit(NOOP);
    assertEquals(MAX_ALLOWED_METRIC_PROBES, acceptor.getDefinitions().size());
  }

  @Test
  public void maxSpanProbes() {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    for (int i = 0; i < 200; i++) {
      SpanProbe probe =
          SpanProbe.builder()
              .probeId(String.valueOf(i), 0)
              .where("java.lang.String", "concat" + i)
              .build();
      acceptSpanProbe(listener, probe);
    }
    listener.commit(NOOP);
    assertEquals(MAX_ALLOWED_SPAN_PROBES, acceptor.getDefinitions().size());
  }

  @Test
  public void maxSpanDecorationProbes() {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    for (int i = 0; i < 200; i++) {
      SpanDecorationProbe probe =
          SpanDecorationProbe.builder()
              .probeId(String.valueOf(i), 0)
              .where("java.lang.String", "concat" + i)
              .build();
      acceptSpanDecorationProbe(listener, probe);
    }
    listener.commit(NOOP);
    assertEquals(MAX_ALLOWED_SPAN_DECORATION_PROBES, acceptor.getDefinitions().size());
  }

  @Test
  public void parsingException() throws IOException {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
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

  byte[] toContent(Configuration configuration) {
    return DebuggerProductChangesListener.Adapter.CONFIGURATION_JSON_ADAPTER
        .toJson(configuration)
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(MetricProbe probe) {
    return DebuggerProductChangesListener.Adapter.METRIC_PROBE_JSON_ADAPTER
        .toJson(probe)
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(LogProbe probe) {
    return DebuggerProductChangesListener.Adapter.LOG_PROBE_JSON_ADAPTER
        .toJson(probe)
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(SpanProbe probe) {
    return DebuggerProductChangesListener.Adapter.SPAN_PROBE_JSON_ADAPTER
        .toJson(probe)
        .getBytes(StandardCharsets.UTF_8);
  }

  byte[] toContent(SpanDecorationProbe probe) {
    return DebuggerProductChangesListener.Adapter.SPAN_DECORATION_PROBE_JSON_ADAPTER
        .toJson(probe)
        .getBytes(StandardCharsets.UTF_8);
  }

  void acceptConfig(
      DebuggerProductChangesListener listener, Configuration config, String configId) {
    assertDoesNotThrow(() -> listener.accept(createConfigKey(configId), toContent(config), NOOP));
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

  void removeSpanProbe(DebuggerProductChangesListener listener, SpanProbe probe) {
    assertDoesNotThrow(() -> listener.remove(createConfigKey("spanProbe_" + probe.getId()), NOOP));
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

  Configuration.FilterList createFilteredList() {
    return new Configuration.FilterList(
        Collections.singletonList("datadog"), Collections.singletonList("class1"));
  }

  ParsedConfigKey createConfigKey(String configId) {
    return ParsedConfigKey.parse("datadog/2/LIVE_DEBUGGING/" + configId + "/config");
  }
}
