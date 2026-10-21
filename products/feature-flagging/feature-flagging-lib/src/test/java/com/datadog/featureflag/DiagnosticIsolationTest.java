package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.Product;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.intake.Intake;
import datadog.trace.test.util.PollingConditions;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.HttpUrl;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.tabletest.junit.TableTest;

class DiagnosticIsolationTest {
  @TableTest({
    "Scenario           | Fail level check",
    "backend throws     | false           ",
    "level check throws | true            "
  })
  void preservesConfigurationAndDeliveryWhenLoggingFails(boolean failLevelCheck) throws Exception {
    LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
    TurboFilter failure =
        new TurboFilter() {
          @Override
          public FilterReply decide(
              Marker marker,
              Logger logger,
              Level level,
              String format,
              Object[] params,
              Throwable error) {
            if (logger.getName().startsWith("com.datadog.featureflag")
                && (failLevelCheck || format != null)) {
              throw new AssertionError("broken logger");
            }
            return FilterReply.NEUTRAL;
          }
        };
    failure.start();
    logging.addTurboFilter(failure);
    FeatureFlaggingGateway.ConfigListener listener =
        mock(FeatureFlaggingGateway.ConfigListener.class);
    FeatureFlaggingGateway.addConfigListener(listener);
    try {
      Config config = mock(Config.class);
      SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
      ConfigurationPoller poller = mock(ConfigurationPoller.class);
      when(sco.configurationPoller(config)).thenReturn(poller);
      try (RemoteConfigServiceImpl service = new RemoteConfigServiceImpl(sco, config)) {
        service.init();
        ServerConfiguration parsed =
            UniversalFlagConfigParser.INSTANCE.deserialize(
                "{\"flags\":{\"bad\":42}}".getBytes(UTF_8));
        assertEquals(1, parsed.invalidFlags.size());
        service.accept("config", parsed, null);
        verify(listener).accept(parsed);
      }
      verify(poller).removeListeners(Product.FFE_FLAGS);
      verify(poller).stop();

      AgentlessConfigurationSource.UfcHttpClient client =
          mock(AgentlessConfigurationSource.UfcHttpClient.class);
      ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
      HttpUrl endpoint = HttpUrl.get("http://localhost/config");
      when(client.fetch(eq(endpoint), eq(config), any()))
          .thenReturn(new AgentlessConfigurationSource.UfcHttpResponse(503, null, null))
          .thenReturn(
              new AgentlessConfigurationSource.UfcHttpResponse(
                  200,
                  "v1",
                  "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":{\"flags\":{}}}}"
                      .getBytes(UTF_8)));
      try (AgentlessConfigurationSource source =
          new AgentlessConfigurationSource(endpoint, config, 1000, client, executor)) {
        source.init();
        assertTrue(source.pollOnce());
      }
      verify(client).cancel();
      verify(executor).shutdownNow();

      BackendApi backend = mock(BackendApi.class);
      BackendApiFactory backendFactory = mock(BackendApiFactory.class);
      when(backendFactory.createEvpProxyApi(Intake.EVENT_PLATFORM, true)).thenReturn(backend);
      FeatureFlagBackendApiFactory factory =
          new FeatureFlagBackendApiFactory(config, backendFactory, FeatureFlagEventType.EXPOSURE);
      ExposureWriterImpl writer = new ExposureWriterImpl(16, 1, MILLISECONDS, factory, config);
      PollingConditions poll = new PollingConditions(5);
      try {
        writer.init();
        writer.accept(
            new ExposureEvent(
                1,
                new Allocation("allocation"),
                new Flag("flag"),
                new Variant("variant"),
                new Subject("user", emptyMap())));
        poll.eventually(() -> verify(backend).post(any(), any(), any(), any(), eq(false)));
      } finally {
        writer.close();
      }
      poll.eventually(() -> assertFalse(writer.isSerializerThreadAlive()));
    } finally {
      FeatureFlaggingGateway.removeConfigListener(listener);
      FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
      logging.getTurboFilterList().remove(failure);
      failure.stop();
    }
  }
}
