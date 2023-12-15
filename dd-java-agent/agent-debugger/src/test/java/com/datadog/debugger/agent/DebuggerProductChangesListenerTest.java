package com.datadog.debugger.agent;

import static com.datadog.debugger.util.LogProbeTestHelper.parseTemplate;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.SpanProbe;
import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.trace.api.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
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

  final ConfigurationChangesListener.PollingHinterNoop pollingHinter =
      new ConfigurationChangesListener.PollingHinterNoop();

  class SimpleAcceptor implements DebuggerProductChangesListener.ConfigurationAcceptor {
    private Configuration configuration;

    @Override
    public void accept(Configuration configuration) {
      this.configuration = configuration;
    }

    public Configuration getConfiguration() {
      return configuration;
    }
  }

  @BeforeEach
  void setUp() {
    lenient().when(tracerConfig.getServiceName()).thenReturn(SERVICE_NAME);
  }

  @Test
  public void testNoConfiguration() {
    Configuration emptyConfig = Configuration.builder().setService(SERVICE_NAME).build();
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    listener.commit(pollingHinter);

    Assertions.assertEquals(emptyConfig, acceptor.getConfiguration());
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
    listener.commit(pollingHinter);

    Assertions.assertEquals(config, acceptor.getConfiguration());
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
                createConfigKey(UUID.randomUUID().toString()), toContent(config), pollingHinter));

    listener.commit(pollingHinter);

    Assertions.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).build(), acceptor.getConfiguration());
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
    listener.commit(pollingHinter);
    Assertions.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).add(metricProbe).build(),
        acceptor.getConfiguration());

    acceptLogProbe(listener, logProbe);
    listener.commit(pollingHinter);
    Assertions.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).add(metricProbe).add(logProbe).build(),
        acceptor.getConfiguration());

    acceptSpanProbe(listener, spanProbe);
    listener.commit(pollingHinter);
    Assertions.assertEquals(
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(metricProbe)
            .add(logProbe)
            .add(spanProbe)
            .build(),
        acceptor.getConfiguration());

    removeMetricProbe(listener, metricProbe);
    listener.commit(pollingHinter);
    Assertions.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).add(logProbe).add(spanProbe).build(),
        acceptor.getConfiguration());

    removeLogProbe(listener, logProbe);
    listener.commit(pollingHinter);
    Assertions.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).add(spanProbe).build(),
        acceptor.getConfiguration());

    removeSpanProbe(listener, spanProbe);
    listener.commit(pollingHinter);
    Assertions.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).build(), acceptor.getConfiguration());
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
    listener.commit(pollingHinter);
    Configuration expectedConfig =
        Configuration.builder().add(config).add(logProbeWithSnapshot).build();
    Assertions.assertEquals(expectedConfig.getService(), acceptor.getConfiguration().getService());
    Assertions.assertEquals(
        expectedConfig.getMetricProbes(), acceptor.getConfiguration().getMetricProbes());
    Assertions.assertTrue(
        acceptor.getConfiguration().getLogProbes().contains(logProbeWithSnapshot));
    Assertions.assertTrue(acceptor.getConfiguration().getLogProbes().contains(logProbe));
  }

  @Test
  public void badConfigIDFailsToAccept() throws IOException {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    listener.accept(createConfigKey("bad-config-id"), null, pollingHinter);
    assertNull(acceptor.configuration);
  }

  @Test
  public void createNewInstancesForLogProbe() {
    SimpleAcceptor acceptor = new SimpleAcceptor();
    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);
    LogProbe logProbe = createLogProbe(UUID.randomUUID().toString());
    acceptLogProbe(listener, logProbe);
    listener.commit(pollingHinter);
    LogProbe receivedProbe = acceptor.getConfiguration().getLogProbes().iterator().next();
    listener.commit(pollingHinter);
    LogProbe receivedProbe2 = acceptor.getConfiguration().getLogProbes().iterator().next();
    assertNotSame(receivedProbe, receivedProbe2);
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

  void acceptConfig(
      DebuggerProductChangesListener listener, Configuration config, String configId) {
    assertDoesNotThrow(
        () -> listener.accept(createConfigKey(configId), toContent(config), pollingHinter));
  }

  void acceptMetricProbe(DebuggerProductChangesListener listener, MetricProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("metricProbe_" + probe.getId()), toContent(probe), pollingHinter));
  }

  void removeMetricProbe(DebuggerProductChangesListener listener, MetricProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("metricProbe_" + probe.getId()), pollingHinter));
  }

  void acceptLogProbe(DebuggerProductChangesListener listener, LogProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("logProbe_" + probe.getId()), toContent(probe), pollingHinter));
  }

  void removeLogProbe(DebuggerProductChangesListener listener, LogProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("logProbe_" + probe.getId()), pollingHinter));
  }

  void acceptSpanProbe(DebuggerProductChangesListener listener, SpanProbe probe) {
    assertDoesNotThrow(
        () ->
            listener.accept(
                createConfigKey("spanProbe_" + probe.getId()), toContent(probe), pollingHinter));
  }

  void removeSpanProbe(DebuggerProductChangesListener listener, SpanProbe probe) {
    assertDoesNotThrow(
        () -> listener.remove(createConfigKey("spanProbe_" + probe.getId()), pollingHinter));
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
