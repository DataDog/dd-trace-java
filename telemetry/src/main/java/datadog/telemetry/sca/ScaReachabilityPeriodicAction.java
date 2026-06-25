package datadog.telemetry.sca;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.dependency.Dependency;
import datadog.telemetry.dependency.DependencyService;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.CveSnapshot;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.DependencySnapshot;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * corresponding JAR has been resolved. In that case, {@link #knownDeps} may not yet have an entry
 * for the dep. Unmatched snapshots are emitted immediately without source/hash so CVE data is never
 * delayed; when the dep is eventually resolved and stored in {@link #knownDeps}, subsequent CVE
 * emissions (e.g., after a method hit) will include source/hash automatically.
 *
 * <p>When the JAR is resolved in a later heartbeat (Step 2) but no CVE is pending (the CVE was
 * drained in a prior heartbeat), Step 2 calls {@link
 * ScaReachabilityDependencyRegistry#peekSnapshot} to retrieve the current CVE state without marking
 * it pending again. This prevents emitting {@code metadata:[]} and overwriting the CVE state the
 * backend already received.
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
  @VisibleForTesting
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
        Dependency previous = knownDeps.put(key, dep);
        DependencySnapshot snapshot = snapshotByKey.remove(key);
        if (snapshot == null && isNewPhysicalCopy(previous, dep)) {
          // First detection of this physical JAR: peek CVE state so we can enrich the emission
          // instead of overwriting the backend's state with metadata:[]. Skip when the same
          // physical copy is re-detected (same source/hash from a different classloader) —
          // peekSnapshot would re-emit a stale hit already reported.
          snapshot = ScaReachabilityDependencyRegistry.INSTANCE.peekSnapshot(dep.name, dep.version);
        }
        if (snapshot != null) {
          telService.addDependency(
              new Dependency(dep.name, dep.version, dep.source, dep.hash, buildMetadata(snapshot)));
        } else if (isNewPhysicalCopy(previous, dep)) {
          // First detection of this physical copy, no CVE state yet — metadata:[] signals "SCA is
          // monitoring this dep".
          telService.addDependency(
              new Dependency(dep.name, dep.version, dep.source, dep.hash, Collections.emptyList()));
        }
        // Re-detection of the same physical copy (same source/hash) with no state change: nothing
        // to emit. The backend already received this dep.
      }
    }

    // Step 3: handle CVE state changes for deps not in DependencyService this heartbeat.
    // Always emit — never block CVE data. Use knownDeps for source/hash enrichment when the JAR
    // was resolved in a prior heartbeat; otherwise emit without source/hash so the backend still
    // receives the CVE state (it may correlate by name:version alone, and subsequent emissions
    // for the same dep — triggered by method hits — will carry source/hash once knownDeps is
    // populated).
    for (DependencySnapshot snapshot : snapshotByKey.values()) {
      String key = ScaReachabilityDependencyRegistry.depKey(snapshot.artifact, snapshot.version);
      Dependency known = knownDeps.get(key);
      if (known != null) {
        // Dep was resolved in a prior heartbeat — emit enriched with source/hash.
        telService.addDependency(
            new Dependency(
                known.name, known.version, known.source, known.hash, buildMetadata(snapshot)));
      } else {
        // Dep not yet resolved — emit without source/hash so CVE data is not delayed.
        // When the dep is eventually resolved (stored in knownDeps via Step 2), subsequent
        // CVE emissions (e.g., after a method hit) will include source/hash automatically.
        telService.addDependency(
            new Dependency(
                snapshot.artifact, snapshot.version, null, null, buildMetadata(snapshot)));
      }
    }
  }

  /**
   * Decides whether {@code dep} represents a new physical copy of an artifact that the backend has
   * not yet seen, as opposed to a re-detection of a JAR already reported.
   *
   * <p>A "new physical copy" is a distinct file on disk. Two copies share the same {@code
   * name@version} key but differ in {@code source} (the path/URI the JAR was loaded from) and/or
   * {@code hash} (the content digest). A "re-detection" is the exact same physical file surfacing
   * again, carrying an identical {@code source} and {@code hash}.
   *
   * <p>Returns {@code true} when:
   *
   * <ul>
   *   <li>{@code previous == null} — first time we see this {@code name@version} key, or
   *   <li>{@code dep.source} differs from {@code previous.source}, or
   *   <li>{@code dep.hash} differs from {@code previous.hash}.
   * </ul>
   *
   * <p>{@link Objects#equals} is used for both comparisons because {@code source} and {@code hash}
   * may legitimately be {@code null} (e.g. JARs whose origin or digest could not be resolved), and
   * a plain {@code ==}/{@code .equals} would either NPE or misclassify two null values.
   *
   * <p>Case this protects (must emit both): a Tomcat container running two webapps that each ship
   * their own physical copy of the same artifact under different {@code WEB-INF/lib} paths. The
   * copies have different {@code source}/{@code hash}, so both are reported — matching the behavior
   * of the legacy {@link datadog.telemetry.dependency.DependencyPeriodicAction}.
   *
   * <p>Case this suppresses (re-detection, do not re-emit): a Spring Boot fat JAR where {@code
   * LaunchedURLClassLoader} reports the same nested JAR from the same URI on multiple heartbeats.
   * The {@code source}/{@code hash} are identical, so the dep is emitted only once.
   */
  private static boolean isNewPhysicalCopy(Dependency previous, Dependency dep) {
    if (previous == null) {
      return true;
    }
    if (!Objects.equals(dep.source, previous.source)) {
      return true;
    }
    return !Objects.equals(dep.hash, previous.hash);
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
