package datadog.telemetry.dependency;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.telemetry.TelemetryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DependencyPeriodicActionTest {

  private final DependencyService depService = mock(DependencyService.class);
  private final DependencyPeriodicAction periodicAction = new DependencyPeriodicAction(depService);
  private final TelemetryService telemetryService = mock(TelemetryService.class);

  @Test
  void transformsDependenciesAndPushesThemToTheTelemetryService() {
    Dependency dependency = new Dependency("name", "1.2.3", "name-1.2.3.jar", "DEADBEEF");
    when(depService.drainDeterminedDependencies()).thenReturn(singletonList(dependency));

    periodicAction.doIteration(telemetryService);

    verify(depService).drainDeterminedDependencies();
    verifyNoMoreInteractions(depService);
    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telemetryService).addDependency(captor.capture());
    verifyNoMoreInteractions(telemetryService);
    Dependency addedDependency = captor.getValue();
    assertEquals("name", addedDependency.name);
    assertEquals("1.2.3", addedDependency.version);
    assertEquals("DEADBEEF", addedDependency.hash);
    assertNull(addedDependency.reachabilityMetadata);
  }
}
