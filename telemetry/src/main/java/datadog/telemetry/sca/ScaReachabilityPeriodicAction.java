package datadog.telemetry.sca;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.dependency.Dependency;
import datadog.telemetry.dependency.DependencyService;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.CveSnapshot;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.DependencySnapshot;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports SCA Reachability state on each telemetry heartbeat, implementing the RFC stateful model.
 *
 * <p>When SCA is enabled this action replaces {@link
 * datadog.telemetry.dependency.DependencyPeriodicAction} as the sole emitter of {@code
 * app-dependencies-loaded} events. It merges two sources into a single entry per dependency per
 * heartbeat:
 *
 * <ol>
 *   <li>{@link DependencyService} - newly detected JARs (same source as DependencyPeriodicAction).
 *       Emitted with {@code metadata:[]} if no CVE state exists yet, or with the full CVE metadata
 *       if a state is already pending.
 *   <li>{@link ScaReachabilityDependencyRegistry} - CVE state changes (registration and hits) for
 *       dependencies detected in previous heartbeats.
 * </ol>
 *
 * <p>This ensures one entry per {@code name:version} per heartbeat - no duplicates.
 *
 * <p>The key invariant: whenever any CVE's state changes, ALL CVEs for the same dependency are
 * re-reported together so the backend always has a complete picture.
 */
public final class ScaReachabilityPeriodicAction
    implements TelemetryRunnable.TelemetryPeriodicAction {

  private final DependencyService dependencyService;

  public ScaReachabilityPeriodicAction(DependencyService dependencyService) {
    this.dependencyService = dependencyService;
  }

  @Override
  public void doIteration(TelemetryService telService) {
    // Trigger pending retransformations (method-level symbols on already-loaded classes, or
    // classes where JAR version resolution failed at load time and needs a retry).
    Runnable work = ScaReachabilityDependencyRegistry.INSTANCE.getPeriodicWorkCallback();
    if (work != null) {
      work.run();
    }

    // Step 1: drain registry → map keyed by "artifact@version" for O(1) lookup below.
    List<DependencySnapshot> pending =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    Map<String, DependencySnapshot> snapshotByKey = new HashMap<>(pending.size() * 2);
    for (DependencySnapshot snapshot : pending) {
      snapshotByKey.put(
          ScaReachabilityDependencyRegistry.depKey(snapshot.artifact, snapshot.version), snapshot);
    }

    // Step 2: drain DependencyService (newly detected JARs this heartbeat).
    // For each new dep: merge with CVE state if present, otherwise emit metadata:[].
    if (dependencyService != null) {
      for (Dependency dep : dependencyService.drainDeterminedDependencies()) {
        String key = ScaReachabilityDependencyRegistry.depKey(dep.name, dep.version);
        DependencySnapshot snapshot = snapshotByKey.remove(key);
        if (snapshot != null) {
          // New dep AND has CVE state - emit the full picture in one entry.
          telService.addDependency(
              new Dependency(dep.name, dep.version, dep.source, dep.hash, buildMetadata(snapshot)));
        } else {
          // New dep, no CVE state yet - metadata:[] signals "SCA is monitoring this dep".
          telService.addDependency(
              new Dependency(dep.name, dep.version, dep.source, dep.hash, Collections.emptyList()));
        }
      }
    }

    // Step 3: emit remaining snapshots (CVE state changes for deps detected in prior heartbeats).
    for (DependencySnapshot snapshot : snapshotByKey.values()) {
      telService.addDependency(
          new Dependency(snapshot.artifact, snapshot.version, null, null, buildMetadata(snapshot)));
    }
  }

  private static List<String> buildMetadata(DependencySnapshot snapshot) {
    List<String> metadataValues = new ArrayList<>(snapshot.cves.size());
    for (CveSnapshot cve : snapshot.cves) {
      metadataValues.add(buildMetadataValue(cve));
    }
    return metadataValues;
  }

  /**
   * Builds the stringified JSON value for one CVE snapshot, per RFC:
   *
   * <ul>
   *   <li>Not yet reached: {@code {"id":"GHSA-xxx","reached":[]}}
   *   <li>Reached: {@code
   *       {"id":"GHSA-xxx","reached":[{"path":"com.foo.Bar","symbol":"...","line":N}]}}
   * </ul>
   *
   * <p>Values are JSON-escaped via {@link #jsonEscape} even though GHSA IDs, JVM class names, and
   * method names are structurally guaranteed not to contain {@code "} or {@code \} by the JVM spec.
   * The escaping is a safety net against future changes to the value sources.
   */
  static String buildMetadataValue(CveSnapshot cve) {
    ScaReachabilityHit hit = cve.hit;
    if (hit == null) {
      // CVE known but no callsite yet - signals "monitoring, not reached"
      return "{\"id\":\"" + jsonEscape(cve.vulnId) + "\",\"reached\":[]}";
    }
    // CVE has been reached - include the callsite
    return "{\"id\":\""
        + jsonEscape(hit.vulnId())
        + "\",\"reached\":[{\"path\":\""
        + jsonEscape(hit.className())
        + "\",\"symbol\":\""
        + jsonEscape(hit.symbolName())
        + "\",\"line\":"
        + hit.line()
        + "}]}";
  }

  /** Escapes a string for embedding in a JSON string literal. */
  private static String jsonEscape(String value) {
    if (value.indexOf('"') == -1 && value.indexOf('\\') == -1) {
      return value; // fast path: no escaping needed (the common case)
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
