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
 *   <li>{@link DependencyService} - newly detected JARs. Emitted with {@code metadata:[]} if no CVE
 *       state exists yet, or merged with CVE metadata if a state is already pending. Each resolved
 *       dependency is stored in {@link #knownDeps} for future lookups.
 *   <li>{@link ScaReachabilityDependencyRegistry} - CVE state changes (registration and hits).
 * </ol>
 *
 * <p>This ensures one entry per {@code name:version} per heartbeat - no duplicates.
 *
 * <p>The key invariant: whenever any CVE's state changes, ALL CVEs for the same dependency are
 * re-reported together so the backend always has a complete picture.
 *
 * <h3>Timing invariant</h3>
 *
 * <p>{@link DependencyService} resolves JARs asynchronously. A CVE may be registered before the
 * corresponding JAR has been resolved (e.g., a lazily-loaded class whose JAR is still in the
 * resolution queue). In that case, {@link #knownDeps} may not yet have an entry for the dep, and
 * emitting the CVE snapshot without source/hash would prevent the backend from correlating it with
 * a known dependency. To handle this, unmatched snapshots are re-marked as pending in the registry
 * and retried on the next heartbeat, when {@link DependencyService} is likely to have resolved the
 * JAR.
 */
public final class ScaReachabilityPeriodicAction
    implements TelemetryRunnable.TelemetryPeriodicAction {

  private final DependencyService dependencyService;

  /**
   * Persistent across heartbeats: accumulates every dep resolved by {@link DependencyService}.
   * Keyed by {@link ScaReachabilityDependencyRegistry#depKey(String, String)}.
   *
   * <p>This cache allows Step 3 to enrich CVE snapshots with {@code source} and {@code hash} even
   * when the dep was drained from {@link DependencyService} in an earlier heartbeat than the CVE
   * hit.
   */
  private final Map<String, Dependency> knownDeps = new HashMap<>();

  public ScaReachabilityPeriodicAction(DependencyService dependencyService) {
    this.dependencyService = dependencyService;
  }

  /**
   * Pre-populates {@link #knownDeps} with a dep entry. Used in tests to simulate a dep that was
   * resolved by {@link DependencyService} in a prior heartbeat without running a full iteration.
   */
  void addKnownDepForTesting(String name, String version) {
    knownDeps.put(
        ScaReachabilityDependencyRegistry.depKey(name, version),
        new Dependency(name, version, null, null));
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
    // Store each dep in knownDeps regardless of whether it has a CVE match — future heartbeats
    // with CVE hits will look it up in Step 3.
    if (dependencyService != null) {
      for (Dependency dep : dependencyService.drainDeterminedDependencies()) {
        String key = ScaReachabilityDependencyRegistry.depKey(dep.name, dep.version);
        knownDeps.put(key, dep);
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

    // Step 3: handle CVE state changes for deps not in DependencyService this heartbeat.
    // Look up knownDeps to enrich with source/hash; if the dep is not yet known (JAR still
    // resolving), re-mark it as pending and retry next heartbeat instead of emitting without
    // source/hash (which the backend cannot correlate with a known dependency).
    for (DependencySnapshot snapshot : snapshotByKey.values()) {
      String key = ScaReachabilityDependencyRegistry.depKey(snapshot.artifact, snapshot.version);
      Dependency known = knownDeps.get(key);
      if (known != null) {
        // Dep resolved in a prior heartbeat - emit with source/hash for backend correlation.
        telService.addDependency(
            new Dependency(
                known.name, known.version, known.source, known.hash, buildMetadata(snapshot)));
      } else {
        // Dep not yet resolved by DependencyService - keep pending and retry next heartbeat.
        ScaReachabilityDependencyRegistry.INSTANCE.markPending(snapshot.artifact, snapshot.version);
      }
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
