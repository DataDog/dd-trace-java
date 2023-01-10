package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.lenient;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import datadog.remoteconfig.ConfigurationChangesListener;
import datadog.remoteconfig.state.ParsedConfigKey;
import datadog.trace.api.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import org.junit.Assert;
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

    Assert.assertEquals(emptyConfig, acceptor.getConfiguration());
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

    Assert.assertEquals(config, acceptor.getConfiguration());
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

    Assert.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).build(), acceptor.getConfiguration());
  }

  @Test
  public void testMultipleSingleProbesConfigurations() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);

    MetricProbe metricProbe = createMetricProbe(UUID.randomUUID().toString());
    LogProbe logProbe = createLogProbe(UUID.randomUUID().toString());

    acceptMetricProbe(listener, metricProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).add(metricProbe).build(),
        acceptor.getConfiguration());

    acceptLogProbe(listener, logProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).add(metricProbe).add(logProbe).build(),
        acceptor.getConfiguration());

    removeMetricProbe(listener, metricProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
        Configuration.builder().setService(SERVICE_NAME).add(logProbe).build(),
        acceptor.getConfiguration());

    removeLogProbe(listener, logProbe);
    listener.commit(pollingHinter);
    Assert.assertEquals(
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

    Configuration config =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .add(metricProbe)
            .add(logProbe)
            .add(new LogProbe.Sampling(3.0))
            .addDenyList(createFilteredList())
            .build();

    acceptLogProbe(listener, logProbeWithSnapshot);
    acceptConfig(listener, config, UUID.randomUUID().toString());
    listener.commit(pollingHinter);
    Configuration expectedConfig =
        Configuration.builder().add(config).add(logProbeWithSnapshot).build();
    Assert.assertEquals(expectedConfig.getService(), acceptor.getConfiguration().getService());
    Assert.assertEquals(
        expectedConfig.getMetricProbes(), acceptor.getConfiguration().getMetricProbes());
    Assert.assertTrue(acceptor.getConfiguration().getLogProbes().contains(logProbeWithSnapshot));
    Assert.assertTrue(acceptor.getConfiguration().getLogProbes().contains(logProbe));
  }

  @Test
  public void badConfigIDFailsToAccept() {
    SimpleAcceptor acceptor = new SimpleAcceptor();

    DebuggerProductChangesListener listener =
        new DebuggerProductChangesListener(tracerConfig, acceptor);

    Assertions.assertThrows(
        IOException.class,
        () -> listener.accept(createConfigKey("bad-config-id"), null, pollingHinter));
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

  LogProbe createLogProbeWithSnapshot(String id) {
    return LogProbe.builder()
        .probeId(id)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .captureSnapshot(true)
        .build();
  }

  MetricProbe createMetricProbe(String id) {
    return MetricProbe.builder()
        .probeId(id)
        .kind(MetricProbe.MetricKind.COUNT)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .build();
  }

  LogProbe createLogProbe(String id) {
    return LogProbe.builder()
        .probeId(id)
        .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
        .template("hello {world}")
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
