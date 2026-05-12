package datadog.telemetry.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.telemetry.ScaReachabilityCollector;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScaReachabilityPeriodicActionTest {

  private TelemetryService telService;
  private ScaReachabilityPeriodicAction action;

  @BeforeEach
  void setUp() {
    ScaReachabilityCollector.INSTANCE.drain(); // clear any leftovers
    telService = mock(TelemetryService.class);
    action = new ScaReachabilityPeriodicAction();
  }

  @Test
  void doesNothingWhenNoHits() {
    action.doIteration(telService);
    verify(telService, never()).addDependency(org.mockito.Mockito.any());
  }

  @Test
  void reportsSingleHit() {
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit(
            "GHSA-test-1234-5678", "com.example:lib", "1.0.0", "com.example.Foo"));

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());

    Dependency dep = captor.getValue();
    assertEquals("com.example:lib", dep.name);
    assertEquals("1.0.0", dep.version);
    assertNull(dep.hash);
    assertNotNull(dep.reachabilityMetadata);
    assertEquals(1, dep.reachabilityMetadata.size());
  }

  @Test
  void metadataValueContainsClinit() {
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit("GHSA-xxx", "com.example:lib", "1.0.0", "com.example.Foo"));

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = forClass(Dependency.class);
    verify(telService).addDependency(captor.capture());
    String value = captor.getValue().reachabilityMetadata.get(0);

    assertTrue(value.contains("\"id\":\"GHSA-xxx\""));
    assertTrue(value.contains("\"path\":\"com.example.Foo\""));
    assertTrue(value.contains("\"symbol\":\"<clinit>\""));
    assertTrue(value.contains("\"line\":1"));
  }

  @Test
  void groupsTwoCvesForSameArtifactVersionIntoOneEntry() {
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit("GHSA-cve-1", "com.example:lib", "1.0.0", "com.example.Foo"));
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit("GHSA-cve-2", "com.example:lib", "1.0.0", "com.example.Bar"));

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());

    Dependency dep = captor.getValue();
    assertEquals(2, dep.reachabilityMetadata.size());
    assertTrue(dep.reachabilityMetadata.stream().anyMatch(v -> v.contains("GHSA-cve-1")));
    assertTrue(dep.reachabilityMetadata.stream().anyMatch(v -> v.contains("GHSA-cve-2")));
  }

  @Test
  void separateEntriesForDifferentArtifacts() {
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit("GHSA-a", "com.example:lib-a", "1.0.0", "com.example.A"));
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit("GHSA-b", "com.example:lib-b", "2.0.0", "com.example.B"));

    action.doIteration(telService);

    verify(telService, times(2)).addDependency(org.mockito.Mockito.any());
  }

  @Test
  void drainsClearsPreviousHits() {
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit("GHSA-x", "com.example:lib", "1.0.0", "com.example.X"));

    action.doIteration(telService);
    verify(telService, times(1)).addDependency(org.mockito.Mockito.any());

    // Second iteration with no new hits — nothing to report
    TelemetryService telService2 = mock(TelemetryService.class);
    action.doIteration(telService2);
    verify(telService2, never()).addDependency(org.mockito.Mockito.any());
  }

  @Test
  void buildMetadataValue_format() {
    ScaReachabilityHit hit =
        new ScaReachabilityHit(
            "GHSA-645p-88qh-w398",
            "com.fasterxml.jackson.core:jackson-databind",
            "2.8.5",
            "com.fasterxml.jackson.databind.ObjectMapper");

    String value = ScaReachabilityPeriodicAction.buildMetadataValue(hit);

    assertEquals(
        "{\"id\":\"GHSA-645p-88qh-w398\","
            + "\"reached\":[{"
            + "\"path\":\"com.fasterxml.jackson.databind.ObjectMapper\","
            + "\"symbol\":\"<clinit>\","
            + "\"line\":1}]}",
        value);
  }
}
