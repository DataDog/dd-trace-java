package datadog.telemetry.sca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.telemetry.TelemetryService;
import datadog.telemetry.dependency.Dependency;
import datadog.telemetry.dependency.DependencyService;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.util.Collections;
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
    DependencyService depService = mock(DependencyService.class);
    when(depService.drainDeterminedDependencies()).thenReturn(Collections.emptyList());
    action = new ScaReachabilityPeriodicAction(depService);
    // Pre-populate knownDeps with the common test dep so registry-only tests can emit via Step 3.
    // This simulates the dep having been resolved by DependencyService in a prior heartbeat.
    action.addKnownDepForTesting("com.example:lib", "1.0.0");
    action.addKnownDepForTesting("com.example:lib-a", "1.0.0");
    action.addKnownDepForTesting("com.example:lib-b", "2.0.0");
    action.addKnownDepForTesting("com.other:lib", "2.0.0");
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

  /**
   * Validates the full RFC heartbeat flow (Heartbeats #2–#6 from the spec):
   *
   * <ol>
   *   <li>Heartbeat after CVE registration: both CVEs reported with reached:[]
   *   <li>Heartbeat with no changes: nothing reported
   *   <li>Heartbeat after first CVE hit: both CVEs reported (one with callsite, one empty)
   *   <li>Heartbeat with no changes: nothing reported
   *   <li>Heartbeat after second CVE hit: both CVEs reported with their respective callsites
   * </ol>
   */
  @Test
  void rfcFullHeartbeatFlow_twoCveSameDepBothHitSequentially() {
    // Phase 1 — CVE registration (Heartbeat #2)
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-cve-1");
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-cve-2");

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor1 = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor1.capture());
    Dependency hb2 = captor1.getValue();
    assertEquals(2, hb2.reachabilityMetadata.size());
    assertTrue(
        hb2.reachabilityMetadata.stream().allMatch(v -> v.contains("\"reached\":[]")),
        "Heartbeat #2: both CVEs must have reached:[]");

    // Phase 2 — No changes (Heartbeat #3)
    TelemetryService telService3 = mock(TelemetryService.class);
    action.doIteration(telService3);
    verify(telService3, never()).addDependency(org.mockito.Mockito.any());

    // Phase 3 — First CVE hit (Heartbeat #4)
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-cve-1", "com.myapp.Controller", "handleRequest", 10);

    TelemetryService telService4 = mock(TelemetryService.class);
    action.doIteration(telService4);

    ArgumentCaptor<Dependency> captor4 = ArgumentCaptor.forClass(Dependency.class);
    verify(telService4, times(1)).addDependency(captor4.capture());
    Dependency hb4 = captor4.getValue();
    assertEquals(2, hb4.reachabilityMetadata.size());
    assertTrue(
        hb4.reachabilityMetadata.stream()
            .anyMatch(v -> v.contains("GHSA-cve-1") && v.contains("\"path\"")),
        "Heartbeat #4: cve-1 must have callsite");
    assertTrue(
        hb4.reachabilityMetadata.stream()
            .anyMatch(v -> v.contains("GHSA-cve-2") && v.contains("\"reached\":[]")),
        "Heartbeat #4: cve-2 must still have reached:[]");

    // Phase 4 — No changes (Heartbeat #5)
    TelemetryService telService5 = mock(TelemetryService.class);
    action.doIteration(telService5);
    verify(telService5, never()).addDependency(org.mockito.Mockito.any());

    // Phase 5 — Second CVE hit (Heartbeat #6)
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-cve-2", "com.myapp.Service", "processData", 44);

    TelemetryService telService6 = mock(TelemetryService.class);
    action.doIteration(telService6);

    ArgumentCaptor<Dependency> captor6 = ArgumentCaptor.forClass(Dependency.class);
    verify(telService6, times(1)).addDependency(captor6.capture());
    Dependency hb6 = captor6.getValue();
    assertEquals(2, hb6.reachabilityMetadata.size());
    assertTrue(
        hb6.reachabilityMetadata.stream()
            .anyMatch(v -> v.contains("GHSA-cve-1") && v.contains("\"path\"")),
        "Heartbeat #6: cve-1 must retain callsite");
    assertTrue(
        hb6.reachabilityMetadata.stream()
            .anyMatch(v -> v.contains("GHSA-cve-2") && v.contains("\"path\"")),
        "Heartbeat #6: cve-2 must now have callsite");
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

  // ---------------------------------------------------------------------------
  // Merge logic: DependencyService + ScaReachabilityDependencyRegistry
  // ---------------------------------------------------------------------------

  private static ScaReachabilityPeriodicAction actionWithDeps(Dependency... deps) {
    DependencyService svc = mock(DependencyService.class);
    org.mockito.Mockito.when(svc.drainDeterminedDependencies())
        .thenReturn(java.util.Arrays.asList(deps));
    return new ScaReachabilityPeriodicAction(svc);
  }

  @Test
  void newDep_noCveState_emitsWithEmptyMetadata() {
    // DependencyService returns a new dep; registry has nothing for it.
    // Expected: one entry with metadata:[] (SCA-active signal, no CVE data yet).
    Dependency incoming = new Dependency("com.example:lib", "1.0.0", "lib-1.0.0.jar", null);
    ScaReachabilityPeriodicAction merged = actionWithDeps(incoming);

    merged.doIteration(telService);

    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency emitted = captor.getValue();
    assertEquals("com.example:lib", emitted.name);
    assertEquals("1.0.0", emitted.version);
    assertNotNull(emitted.reachabilityMetadata, "metadata must not be null when SCA active");
    assertTrue(emitted.reachabilityMetadata.isEmpty(), "metadata must be [] when no CVE state");
  }

  @Test
  void newDep_withCveState_emitsMergedSingleEntry() {
    // DependencyService returns dep X; registry has a pending CVE state for the same dep.
    // Expected: ONE entry with the CVE metadata merged in — no separate dep:[] entry.
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-test-1234");
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-test-1234", "com.myapp.Ctrl", "handle", 10);

    Dependency incoming = new Dependency("com.example:lib", "1.0.0", "lib-1.0.0.jar", "ABCD");
    ScaReachabilityPeriodicAction merged = actionWithDeps(incoming);

    merged.doIteration(telService);

    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency emitted = captor.getValue();
    assertEquals("com.example:lib", emitted.name);
    assertEquals("1.0.0", emitted.version);
    assertEquals("ABCD", emitted.hash, "source/hash from DependencyService must be preserved");
    assertEquals(1, emitted.reachabilityMetadata.size());
    assertTrue(emitted.reachabilityMetadata.get(0).contains("GHSA-test-1234"));
    assertTrue(
        emitted.reachabilityMetadata.get(0).contains("\"path\""),
        "merged entry must include callsite");
  }

  @Test
  void newDepAndUnrelatedCveState_emitsTwoIndependentEntries() {
    // DependencyService returns depA; registry has pending state for depB (different dep).
    // Expected: two separate entries — one for depA (metadata:[]), one for depB (CVE metadata).
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.other:lib", "2.0.0", "GHSA-other-5678");

    Dependency incomingA = new Dependency("com.example:lib", "1.0.0", "lib-1.0.0.jar", null);
    ScaReachabilityPeriodicAction merged = actionWithDeps(incomingA);
    // Simulate com.other:lib having been resolved by DependencyService in a prior heartbeat
    merged.addKnownDepForTesting("com.other:lib", "2.0.0");

    merged.doIteration(telService);

    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(2)).addDependency(captor.capture());
    java.util.List<Dependency> emitted = captor.getAllValues();

    Dependency depA =
        emitted.stream()
            .filter(d -> "com.example:lib".equals(d.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("dep not found"));
    Dependency depB =
        emitted.stream()
            .filter(d -> "com.other:lib".equals(d.name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("dep not found"));

    assertTrue(depA.reachabilityMetadata.isEmpty(), "depA: no CVE state → metadata:[]");
    assertTrue(
        depB.reachabilityMetadata.get(0).contains("GHSA-other-5678"), "depB: must carry CVE state");
  }

  // ---------------------------------------------------------------------------
  // knownDeps / timing invariant tests
  // ---------------------------------------------------------------------------

  /**
   * Dep resolved by DependencyService in heartbeat N; CVE fires in heartbeat N+1. The dep is
   * already in knownDeps, so Step 3 emits it with source/hash.
   */
  @Test
  void cveFiresAfterDepResolved_usesKnownDepsForSourceHash() {
    DependencyService svc = mock(DependencyService.class);
    // Heartbeat 1: DependencyService returns the dep, no CVE yet
    when(svc.drainDeterminedDependencies())
        .thenReturn(
            Collections.singletonList(
                new Dependency("com.example:lib", "1.0.0", "lib.jar", "ABCD")))
        .thenReturn(Collections.emptyList()); // heartbeat 2: nothing new
    ScaReachabilityPeriodicAction merged = new ScaReachabilityPeriodicAction(svc);

    // Heartbeat 1: dep detected, no CVE → emits metadata:[]
    merged.doIteration(telService);

    // CVE fires between heartbeat 1 and 2
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-late");

    // Heartbeat 2: DependencyService is empty, but CVE is pending
    TelemetryService telService2 = mock(TelemetryService.class);
    merged.doIteration(telService2);

    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService2, times(1)).addDependency(captor.capture());
    Dependency emitted = captor.getValue();
    assertEquals("lib.jar", emitted.source, "source from knownDeps must be preserved");
    assertEquals("ABCD", emitted.hash, "hash from knownDeps must be preserved");
    assertTrue(emitted.reachabilityMetadata.get(0).contains("GHSA-late"));
  }

  /**
   * CVE fires before DependencyService has resolved the dep (timing race).
   *
   * <p>Step 3 emits immediately without source/hash so CVE data is never delayed (system tests need
   * data within seconds). When the dep is later resolved and stored in knownDeps, subsequent CVE
   * emissions (e.g., after a method hit) carry source/hash automatically.
   */
  @Test
  void cveFiresBeforeDepResolved_emitsImmediatelyWithoutSourceHash() {
    DependencyService svc = mock(DependencyService.class);
    // Heartbeat 1: DependencyService is empty (dep not yet resolved)
    when(svc.drainDeterminedDependencies()).thenReturn(Collections.emptyList());
    ScaReachabilityPeriodicAction merged = new ScaReachabilityPeriodicAction(svc);

    // CVE fires before DependencyService resolves the dep
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-race");
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-race", "com.app.Ctrl", "handle", 10);

    // Heartbeat 1: emits immediately without source/hash — CVE data is not delayed
    merged.doIteration(telService);

    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency emitted = captor.getValue();
    assertNull(emitted.source, "source is null when dep not yet in knownDeps");
    assertNull(emitted.hash, "hash is null when dep not yet in knownDeps");
    assertTrue(emitted.reachabilityMetadata.get(0).contains("GHSA-race"));
    assertTrue(emitted.reachabilityMetadata.get(0).contains("\"path\""), "must include callsite");
  }

  /**
   * Regression test for the Codex P2 race condition: CVE registered in HB1 (dep not yet resolved),
   * emitted immediately without source/hash. In HB2 DependencyService resolves the JAR — if we
   * naively emit metadata:[] because there is no pending snapshot, the backend overwrites the CVE
   * state it already received. The fix uses peekSnapshot so HB2 emits the CVE metadata together
   * with the now-known source/hash.
   */
  @Test
  void cveRegisteredBeforeDepResolved_step2PeeksCveStateInLaterHeartbeat() {
    DependencyService svc = mock(DependencyService.class);

    // HB1: CVE registered, dep not yet resolved by DependencyService
    when(svc.drainDeterminedDependencies()).thenReturn(Collections.emptyList());
    ScaReachabilityPeriodicAction action = new ScaReachabilityPeriodicAction(svc);
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-peek");

    action.doIteration(telService);

    // HB1 emits once: CVE state without source/hash (dep not yet resolved)
    verify(telService, times(1)).addDependency(any(Dependency.class));
    reset(telService);

    // HB2: DependencyService now resolves the JAR; no new CVE activity (pendingReport=false)
    Dependency resolved = new Dependency("com.example:lib", "1.0.0", "lib-1.0.0.jar", "CAFEBABE");
    when(svc.drainDeterminedDependencies()).thenReturn(Collections.singletonList(resolved));

    action.doIteration(telService);

    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency emitted = captor.getValue();
    assertEquals("lib-1.0.0.jar", emitted.source, "HB2 must include resolved source");
    assertEquals("CAFEBABE", emitted.hash, "HB2 must include resolved hash");
    assertFalse(
        emitted.reachabilityMetadata.isEmpty(),
        "HB2 must not emit metadata:[] — CVE state must be preserved via peekSnapshot");
    assertTrue(
        emitted.reachabilityMetadata.get(0).contains("GHSA-peek"),
        "HB2 metadata must contain the CVE id");
  }

  /**
   * Regression test for the multi-classloader re-detection bug.
   *
   * <p>{@link DependencyService} can report the same JAR on consecutive heartbeats when it
   * discovers it from multiple classloaders in the same application. The CVE hit must be emitted
   * exactly once — on the heartbeat where the CVE state is pending or first detected via {@code
   * peekSnapshot}. Re-detections of the same JAR with no new CVE activity must produce no emission.
   *
   * <p>Without the fix, Step 2 called {@code peekSnapshot} unconditionally when {@code
   * snapshotByKey} had no entry, causing the hit to be re-emitted on every subsequent heartbeat
   * where {@code DependencyService} reported the JAR again (e.g. from a different classloader).
   */
  @Test
  void depReDetectedByDependencyService_hitNotReEmittedOnSubsequentDetection() {
    DependencyService svc = mock(DependencyService.class);
    Dependency dep = new Dependency("com.example:lib", "1.0.0", "lib.jar", "HASH");
    // Same dep returned on both heartbeats (simulates multiple classloaders detecting the same JAR)
    when(svc.drainDeterminedDependencies())
        .thenReturn(Collections.singletonList(dep)) // HB1: first detection
        .thenReturn(Collections.singletonList(dep)); // HB2: re-detected from another classloader
    ScaReachabilityPeriodicAction merged = new ScaReachabilityPeriodicAction(svc);

    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-dup");
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-dup", "com.app.Ctrl", "handle", 10);

    // HB1: first detection, CVE is pending → emit merged entry with hit
    merged.doIteration(telService);
    ArgumentCaptor<Dependency> captor1 = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor1.capture());
    assertTrue(
        captor1.getValue().reachabilityMetadata.get(0).contains("\"path\""),
        "HB1 must include the callsite hit");
    reset(telService);

    // HB2: same JAR re-detected, no new CVE activity → must NOT re-emit the hit
    merged.doIteration(telService);
    verify(telService, never()).addDependency(any());
  }

  /**
   * Companion to {@link #depReDetectedByDependencyService_hitNotReEmittedOnSubsequentDetection}:
   * verifies that a REAL new CVE state change (hit arriving between heartbeats) is still emitted
   * correctly even when DependencyService also reports the dep again on the same heartbeat.
   */
  @Test
  void depReDetectedWithNewHit_emitsOnceWithNewHit() {
    DependencyService svc = mock(DependencyService.class);
    Dependency dep = new Dependency("com.example:lib", "1.0.0", "lib.jar", "HASH");
    when(svc.drainDeterminedDependencies())
        .thenReturn(Collections.singletonList(dep)) // HB1
        .thenReturn(Collections.singletonList(dep)); // HB2
    ScaReachabilityPeriodicAction merged = new ScaReachabilityPeriodicAction(svc);

    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-new");

    // HB1: first detection, CVE pending with reached:[] → emitted once
    merged.doIteration(telService);
    verify(telService, times(1)).addDependency(any());
    reset(telService);

    // Hit recorded between HB1 and HB2
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "1.0.0", "GHSA-new", "com.app.Svc", "exec", 7);

    // HB2: re-detection AND new hit pending (snapshotByKey has the entry) → emit once with hit
    merged.doIteration(telService);
    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    assertTrue(
        captor.getValue().reachabilityMetadata.get(0).contains("\"path\""),
        "HB2 must include the new callsite hit");
  }

  /**
   * Dep and CVE arrive simultaneously (same heartbeat) — existing Step 2 merge path. This existing
   * behavior must still work after the knownDeps refactor.
   */
  @Test
  void cveAndDepArriveSameHeartbeat_step2MergeStillWorks() {
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
        "com.example:lib", "1.0.0", "GHSA-simultaneous");

    Dependency incoming = new Dependency("com.example:lib", "1.0.0", "lib.jar", "HASH");
    ScaReachabilityPeriodicAction merged = actionWithDeps(incoming);

    merged.doIteration(telService);

    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency emitted = captor.getValue();
    assertEquals("lib.jar", emitted.source, "Step 2 merge must preserve source");
    assertTrue(emitted.reachabilityMetadata.get(0).contains("GHSA-simultaneous"));
  }

  /**
   * Regression test for the registry key mismatch for JARs without pom.properties (PR #11614).
   *
   * <p>For JARs without {@code pom.properties}, {@code DependencyResolver.guessFallbackNoPom}
   * produces an artifact-ID-only name (e.g. {@code "junrar"} instead of {@code
   * "com.github.junrar:junrar"}). The transformer must register the CVE under the same name so that
   * the registry key matches what {@code DependencyService} will report.
   *
   * <p>Without the fix, {@code registerCve} was called with {@code entry.artifact()} = {@code
   * "com.github.junrar:junrar"}, while {@code DependencyService} reported {@code dep.name} = {@code
   * "junrar"}. The result: two separate telemetry entries for the same physical JAR — one with
   * {@code metadata:[]} (from Step 2, carrying source/hash but no CVE) and one with the CVE
   * metadata (from Step 3, without source/hash).
   *
   * <p>With the fix, the transformer uses the resolved {@code dep.name} from {@code matchDep} (i.e.
   * {@code "junrar"}) for {@code registerCve}, so the registry and {@code DependencyService} keys
   * match. Step 2 merges them into a single emission with both the CVE metadata and source/hash.
   */
  @Test
  void noPomJar_artifactIdOnlyName_cveAndSourceHashMergedIntoSingleEntry() {
    // Simulates what ScaReachabilityTransformer.processClass() now does after the fix:
    // registerCve with the artifactId-only name that DependencyService will also report.
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("junrar", "7.5.5", "GHSA-hf5p-test");

    // DependencyService returns the dep with the same artifactId-only name (guessFallbackNoPom).
    Dependency incoming = new Dependency("junrar", "7.5.5", "junrar-7.5.5.jar", "CAFEBABE");
    ScaReachabilityPeriodicAction merged = actionWithDeps(incoming);

    merged.doIteration(telService);

    // Must produce exactly ONE emission with BOTH the CVE metadata AND source/hash — not two
    // separate entries (one with CVE but no source/hash, one with source/hash but no CVE).
    ArgumentCaptor<Dependency> captor = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor.capture());
    Dependency emitted = captor.getValue();
    assertEquals(
        "junrar", emitted.name, "name must be the artifactId-only name from DependencyService");
    assertEquals("7.5.5", emitted.version);
    assertEquals(
        "junrar-7.5.5.jar", emitted.source, "source/hash from DependencyService must be preserved");
    assertEquals("CAFEBABE", emitted.hash, "hash from DependencyService must be preserved");
    assertFalse(
        emitted.reachabilityMetadata.isEmpty(),
        "CVE metadata must NOT be lost — a single merged entry must carry both CVE data and source/hash");
    assertTrue(emitted.reachabilityMetadata.get(0).contains("GHSA-hf5p-test"));
  }

  /**
   * Same physical copy (identical source/hash) re-detected on a later heartbeat must NOT be
   * re-emitted — the backend already received it. Mirrors a Spring Boot {@code
   * LaunchedURLClassLoader} reporting the same nested JAR (same URI) again.
   */
  @Test
  void sameSourceHashReDetected_doesNotReEmit() {
    DependencyService svc = mock(DependencyService.class);
    Dependency dep = new Dependency("com.example:lib", "1.0", "lib.jar", "AABB");
    when(svc.drainDeterminedDependencies())
        .thenReturn(Collections.singletonList(dep)) // HB1: first detection
        .thenReturn(Collections.singletonList(dep)); // HB2: same physical copy re-detected
    ScaReachabilityPeriodicAction merged = new ScaReachabilityPeriodicAction(svc);

    // HB1: first detection → emits metadata:[] (SCA monitoring signal)
    merged.doIteration(telService);
    verify(telService, times(1)).addDependency(any(Dependency.class));
    reset(telService);

    // HB2: same source/hash → backend already knows it → no emission
    merged.doIteration(telService);
    verify(telService, never()).addDependency(any());
  }

  /**
   * Two physical copies of the same {@code name@version} but with different source/hash (e.g. two
   * Tomcat webapps each shipping their own copy of the artifact) must BOTH be emitted, matching the
   * legacy {@code DependencyPeriodicAction} behavior.
   */
  @Test
  void differentSourceHashSameKey_emitsBothPhysicalCopies() {
    DependencyService svc = mock(DependencyService.class);
    Dependency copy1 = new Dependency("com.example:lib", "1.0", "/app1/lib.jar", "AABB");
    Dependency copy2 = new Dependency("com.example:lib", "1.0", "/app2/lib.jar", "CCDD");
    when(svc.drainDeterminedDependencies())
        .thenReturn(Collections.singletonList(copy1)) // HB1: first physical copy
        .thenReturn(Collections.singletonList(copy2)); // HB2: distinct physical copy
    ScaReachabilityPeriodicAction merged = new ScaReachabilityPeriodicAction(svc);

    // HB1: first copy → emitted
    merged.doIteration(telService);
    ArgumentCaptor<Dependency> captor1 = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor1.capture());
    assertEquals("/app1/lib.jar", captor1.getValue().source);
    assertEquals("AABB", captor1.getValue().hash);
    reset(telService);

    // HB2: different source/hash → new physical copy → also emitted
    merged.doIteration(telService);
    ArgumentCaptor<Dependency> captor2 = ArgumentCaptor.forClass(Dependency.class);
    verify(telService, times(1)).addDependency(captor2.capture());
    assertEquals("/app2/lib.jar", captor2.getValue().source);
    assertEquals("CCDD", captor2.getValue().hash);
  }
}
