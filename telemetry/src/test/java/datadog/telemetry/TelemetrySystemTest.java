package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.telemetry.dependency.DependencyService;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelemetrySystemTest {

  @AfterEach
  void cleanup() {
    TelemetrySystem.stop();
  }

  @Test
  void installsDependenciesTransformer() {
    Instrumentation instrumentation = mock(Instrumentation.class);

    DependencyService dependencyService = TelemetrySystem.createDependencyService(instrumentation);
    try {
      ArgumentCaptor<ClassFileTransformer> transformerCaptor =
          ArgumentCaptor.forClass(ClassFileTransformer.class);
      verify(instrumentation, times(1)).addTransformer(transformerCaptor.capture());
      assertEquals(
          "datadog.telemetry.dependency.LocationsCollectingTransformer",
          transformerCaptor.getValue().getClass().getName());
    } finally {
      dependencyService.stop();
    }
  }

  @Test
  void createTelemetryThread() {
    TelemetryService telemetryService = mock(TelemetryService.class);
    DependencyService dependencyService = mock(DependencyService.class);

    Thread thread =
        TelemetrySystem.createTelemetryRunnable(telemetryService, dependencyService, true);

    assertNotNull(thread);
  }

  @Test
  @WithConfig(key = GeneralConfig.SITE, value = "datad0g.com")
  @WithConfig(key = GeneralConfig.API_KEY, value = "api-key")
  void startStopTelemetrySystem() {
    Instrumentation instrumentation = mock(Instrumentation.class);

    TelemetrySystem.startTelemetry(instrumentation, sharedCommunicationObjects());

    assertNotNull(TelemetrySystem.getTelemetryThread());

    TelemetrySystem.stop();

    assertTrue(
        TelemetrySystem.getTelemetryThread() == null
            || TelemetrySystem.getTelemetryThread().isInterrupted()
            || !TelemetrySystem.getTelemetryThread().isAlive());
  }

  private SharedCommunicationObjects sharedCommunicationObjects() {
    SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentHttpClient = mock(OkHttpClient.class);
    sco.monitoring = mock(Monitoring.class);
    sco.agentUrl = HttpUrl.get("https://example.com");
    sco.setFeaturesDiscovery(mock(DDAgentFeaturesDiscovery.class));
    return sco;
  }
}
