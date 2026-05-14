package datadog.telemetry.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScaReachabilityPeriodicActionTest {

  private TelemetryService telService;
  private ScaReachabilityPeriodicAction action;

  @BeforeEach
  void setUp() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
    telService = mock(TelemetryService.class);
    action = new ScaReachabilityPeriodicAction();
  }

  @Test
  void doesNothingWhenNoPendingDependencies() {
    action.doIteration(telService);
    verify(telService, never()).addDependency(org.mockito.Mockito.any());
  }

  @Test
  void reportsRegisteredCveWithEmptyReached() {
    // CVE registered but no hit yet → metadata: [{cve-1, reached:[]}]
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-xxx");

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency dep = captor.getValue();
    assertEquals("com.example:lib", dep.name);
    assertEquals(1, dep.reachabilityMetadata.size());
    assertTrue(
        dep.reachabilityMetadata.get(0).contains("\"reached\":[]"),
        "CVE with no hit must have reached:[]");
    assertTrue(dep.reachabilityMetadata.get(0).contains("\"id\":\"GHSA-xxx\""));
  }

  @Test
  void reportsRegisteredCveWithCallsiteAfterHit() {
    // CVE registered, then hit → metadata: [{cve-1, reached:[callsite]}]
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-xxx");
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-xxx", "com.myapp.Service", "process", 42);

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    String metaValue = captor.getValue().reachabilityMetadata.get(0);
    assertTrue(metaValue.contains("\"path\":\"com.myapp.Service\""));
    assertTrue(metaValue.contains("\"symbol\":\"process\""));
    assertTrue(metaValue.contains("\"line\":42"));
    assertFalse(metaValue.contains("\"reached\":[]"), "Hit must not produce empty reached");
  }

  @Test
  void groupsTwoCvesForSameArtifactIntoOneEntry() {
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-cve-1");
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-cve-2");

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency dep = captor.getValue();
    assertEquals(2, dep.reachabilityMetadata.size());
    assertTrue(dep.reachabilityMetadata.stream().anyMatch(v -> v.contains("GHSA-cve-1")));
    assertTrue(dep.reachabilityMetadata.stream().anyMatch(v -> v.contains("GHSA-cve-2")));
  }

  @Test
  void reportsAllCvesWhenOneIsHit() {
    // RFC requirement: when cve-1 is hit, re-report BOTH cve-1 (with callsite) and cve-2 (empty)
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-cve-1");
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-cve-2");
    // First heartbeat: both sent with empty reached
    action.doIteration(telService);

    // Now hit cve-1
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-cve-1", "com.myapp.Svc", "call", 10);

    // Second heartbeat: BOTH CVEs re-reported — cve-1 with callsite, cve-2 still empty
    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = forClass(Dependency.class);
    verify(telService, times(2)).addDependency(captor.capture());
    List<Dependency> reported = captor.getAllValues();
    Dependency secondReport = reported.get(1);
    assertEquals(2, secondReport.reachabilityMetadata.size());
    // cve-1 now has a callsite
    assertTrue(
        secondReport.reachabilityMetadata.stream()
            .anyMatch(v -> v.contains("GHSA-cve-1") && v.contains("\"path\"")));
    // cve-2 still has empty reached
    assertTrue(
        secondReport.reachabilityMetadata.stream()
            .anyMatch(v -> v.contains("GHSA-cve-2") && v.contains("\"reached\":[]")));
  }

  @Test
  void separateEntriesForDifferentArtifacts() {
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib-a", "1.0.0", "GHSA-a");
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib-b", "2.0.0", "GHSA-b");

    action.doIteration(telService);

    verify(telService, times(2)).addDependency(org.mockito.Mockito.any());
  }

  @Test
  void drainsClearsPendingState() {
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-x");

    action.doIteration(telService);
    verify(telService, times(1)).addDependency(org.mockito.Mockito.any());

    // Second iteration with no new state — nothing to report
    TelemetryService telService2 = mock(TelemetryService.class);
    action.doIteration(telService2);
    verify(telService2, never()).addDependency(org.mockito.Mockito.any());
  }

  @Test
  void buildMetadataValue_emptyReachedWhenNoHit() {
    ScaReachabilityDependencyRegistry.CveSnapshot cve =
        new ScaReachabilityDependencyRegistry.CveSnapshot("GHSA-645p-88qh-w398", null);

    String value = ScaReachabilityPeriodicAction.buildMetadataValue(cve);

    assertEquals(
        "{\"id\":\"GHSA-645p-88qh-w398\",\"reached\":[]}",
        value,
        "CVE with no hit must produce reached:[]");
  }

  @Test
  void buildMetadataValue_includesCallsiteWhenHit() {
    ScaReachabilityHit hit =
        new ScaReachabilityHit(
            "GHSA-645p-88qh-w398",
            "com.fasterxml.jackson.core:jackson-databind",
            "2.8.5",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "<clinit>",
            1);
    ScaReachabilityDependencyRegistry.CveSnapshot cve =
        new ScaReachabilityDependencyRegistry.CveSnapshot("GHSA-645p-88qh-w398", hit);

    String value = ScaReachabilityPeriodicAction.buildMetadataValue(cve);

    assertEquals(
        "{\"id\":\"GHSA-645p-88qh-w398\","
            + "\"reached\":[{"
            + "\"path\":\"com.fasterxml.jackson.databind.ObjectMapper\","
            + "\"symbol\":\"<clinit>\","
            + "\"line\":1}]}",
        value);
  }
}
