package datadog.telemetry.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.api.Integration;
import datadog.trace.api.telemetry.IntegrationsCollector;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IntegrationPeriodicActionTest {

  private final IntegrationPeriodicAction periodicAction = new IntegrationPeriodicAction();
  private final TelemetryService telemetryService = mock(TelemetryService.class);

  @Test
  void pushIntegrationsIntoTheTelemetryService() {
    IntegrationsCollector.get().update(Arrays.asList("web", "jdbc"), true);

    periodicAction.doIteration(telemetryService);

    ArgumentCaptor<Integration> captor = forClass(Integration.class);
    verify(telemetryService, times(2)).addIntegration(captor.capture());
    verifyNoMoreInteractions(telemetryService);

    List<Integration> integrations = captor.getAllValues();
    assertMatchingIntegration(integrations, "web");
    assertMatchingIntegration(integrations, "jdbc");
  }

  private void assertMatchingIntegration(List<Integration> integrations, String name) {
    List<Integration> matches =
        integrations.stream()
            .filter(integration -> integration.name.equals(name))
            .collect(Collectors.toList());
    assertEquals(1, matches.size(), "expected exactly one match for " + name);
    assertTrue(matches.get(0).enabled);
  }
}
