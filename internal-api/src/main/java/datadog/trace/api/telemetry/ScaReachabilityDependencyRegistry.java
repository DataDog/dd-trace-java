package datadog.trace.api.telemetry;

import datadog.trace.api.Config;
import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateful registry for SCA Reachability, implementing the RFC heartbeat model.
 *
 * <p>The RFC requires a stateful flow:
 *
 * <ol>
 *   <li>When a class from a vulnerable version is loaded: register the CVE with {@code reached=[]}
 *       and mark as pending — the backend learns that SCA is monitoring this dependency.
 *   <li>When a vulnerable method is called: record the callsite, mark as pending.
 *   <li>On each heartbeat: report ALL CVEs for every dependency that has pending changes (including
 *       those with empty {@code reached}) then clear pending. Empty heartbeat otherwise.
 * </ol>
 *
 * <p>Pattern mirrors {@code WafMetricCollector}: lives in {@code internal-api}, accessible by both
 * the {@code appsec} writer and the {@code telemetry} reader without circular dependencies.
 */
public final class ScaReachabilityDependencyRegistry {

  private static final Logger log =
      LoggerFactory.getLogger(ScaReachabilityDependencyRegistry.class);

  public static final ScaReachabilityDependencyRegistry INSTANCE =
      new ScaReachabilityDependencyRegistry();

  private final int maxTrackedDependencies = Config.get().getAppSecScaMaxTrackedDependencies();
  private final AtomicBoolean capWarningLogged = new AtomicBoolean(false);

  /** Keyed by {@link #depKey(String, String)}. */
  private final ConcurrentHashMap<String, DependencyState> dependencies = new ConcurrentHashMap<>();

  public static String depKey(String artifact, String version) {
    return artifact + "@" + version;
  }

  /**
   * Optional periodic work hook for retransformation of pending method-level classes. Registered by
   * {@code ScaReachabilitySystem}, called by {@code ScaReachabilityPeriodicAction}.
   */
  private volatile Runnable periodicWorkCallback;

  public void setPeriodicWorkCallback(Runnable callback) {
    periodicWorkCallback = callback;
  }

  public Runnable getPeriodicWorkCallback() {
    return periodicWorkCallback;
  }

  /** Clears all state. Used in tests to reset between test cases. */
  @VisibleForTesting
  public void resetForTesting() {
    dependencies.clear();
    capWarningLogged.set(false);
    periodicWorkCallback = null;
  }

  private ScaReachabilityDependencyRegistry() {}

  /**
   * Registers a CVE for a dependency when a class from a vulnerable version is loaded. Creates a
   * new entry with {@code reached=[]} if not already present. Marks the dependency as pending so
   * the next heartbeat reports it (signalling that SCA is monitoring this CVE).
   *
   * <p>Called by {@code ScaReachabilityTransformer} on class load (class-level symbols) and before
   * bytecode injection (method-level symbols).
   */
  public void registerCve(String artifact, String version, String vulnId) {
    String key = depKey(artifact, version);
    if (isCapExceeded(key)) return;
    DependencyState dep =
        dependencies.computeIfAbsent(key, k -> new DependencyState(artifact, version));
    dep.registerCve(vulnId);
  }

  /**
   * Records the first callsite that triggered a vulnerable method. Only the first hit per CVE is
   * stored (RFC: "reporting a single occurrence is sufficient"). Marks the dependency as pending.
   *
   * <p>Called by {@code ScaReachabilitySystem} handler when injected bytecode fires.
   */
  public void recordHit(
      String artifact,
      String version,
      String vulnId,
      String callsiteClass,
      String callsiteSymbol,
      int callsiteLine) {
    String key = depKey(artifact, version);
    if (isCapExceeded(key)) return;
    dependencies
        .computeIfAbsent(key, k -> new DependencyState(artifact, version))
        .recordHit(vulnId, callsiteClass, callsiteSymbol, callsiteLine);
  }

  private boolean isCapExceeded(String key) {
    if (!dependencies.containsKey(key) && dependencies.size() >= maxTrackedDependencies) {
      if (capWarningLogged.compareAndSet(false, true)) {
        log.warn(
            "SCA Reachability: dependency tracking cap ({}) reached, further dependencies will not be tracked. Increase DD_APPSEC_SCA_MAX_TRACKED_DEPENDENCIES to raise the limit.",
            maxTrackedDependencies);
      }
      return true;
    }
    return false;
  }

  /**
   * Returns the current CVE snapshot for a dependency without clearing the pending flag. Used by
   * {@code ScaReachabilityPeriodicAction} when a newly-resolved JAR is not in {@code snapshotByKey}
   * (i.e., the CVE was drained in a prior heartbeat but DependencyService resolved the JAR only
   * now). Without this peek the emission would carry {@code metadata:[]} and overwrite the CVE
   * state already reported.
   *
   * <p>Returns {@code null} if the dependency has no CVE state.
   */
  public DependencySnapshot peekSnapshot(String artifact, String version) {
    DependencyState dep = dependencies.get(depKey(artifact, version));
    if (dep == null || dep.cves.isEmpty()) {
      return null;
    }
    List<CveSnapshot> cveSnapshots = new ArrayList<>(dep.cves.size());
    for (Map.Entry<String, CveState> entry : dep.cves.entrySet()) {
      cveSnapshots.add(new CveSnapshot(entry.getKey(), entry.getValue().hitRef.get()));
    }
    return new DependencySnapshot(artifact, version, Collections.unmodifiableList(cveSnapshots));
  }

  /**
   * Returns a snapshot of all dependencies that have pending changes since the last drain, then
   * clears the pending flag. Called by {@code ScaReachabilityPeriodicAction} on each heartbeat.
   *
   * <p>Each returned {@link DependencySnapshot} contains ALL CVEs for that dependency (both with
   * and without callsite hits), as required by the RFC stateful model.
   */
  public List<DependencySnapshot> drainPendingDependencies() {
    List<DependencySnapshot> result = new ArrayList<>();
    for (DependencyState dep : dependencies.values()) {
      DependencySnapshot snapshot = dep.drainIfPending();
      if (snapshot != null) {
        result.add(snapshot);
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // Internal state classes
  // ---------------------------------------------------------------------------

  /** Mutable state for one (artifact, version) dependency. Thread-safe. */
  public static final class DependencyState {
    public final String artifact;
    public final String version;

    /** CVE ID → first callsite hit, or {@code null} if not yet reached. */
    private final ConcurrentHashMap<String, CveState> cves = new ConcurrentHashMap<>();

    private volatile boolean pendingReport = false;

    DependencyState(String artifact, String version) {
      this.artifact = artifact;
      this.version = version;
    }

    void registerCve(String vulnId) {
      cves.computeIfAbsent(vulnId, k -> new CveState());
      pendingReport = true;
    }

    void recordHit(String vulnId, String callsiteClass, String callsiteSymbol, int callsiteLine) {
      CveState state = cves.computeIfAbsent(vulnId, k -> new CveState());
      // compareAndSet guarantees exactly one callsite is stored (first hit wins).
      // A plain volatile check-then-assign would allow two threads racing on different
      // methods of the same CVE to both see null and both write, violating the invariant.
      ScaReachabilityHit newHit =
          new ScaReachabilityHit(
              vulnId, artifact, version, callsiteClass, callsiteSymbol, callsiteLine);
      if (state.hitRef.compareAndSet(null, newHit)) {
        pendingReport = true;
      }
    }

    /**
     * Returns a snapshot if pending, then clears the pending flag. Returns null if nothing to
     * report.
     */
    DependencySnapshot drainIfPending() {
      if (!pendingReport) {
        return null;
      }
      pendingReport = false;
      List<CveSnapshot> cveSnapshots = new ArrayList<>(cves.size());
      for (Map.Entry<String, CveState> entry : cves.entrySet()) {
        cveSnapshots.add(new CveSnapshot(entry.getKey(), entry.getValue().hitRef.get()));
      }
      return new DependencySnapshot(artifact, version, Collections.unmodifiableList(cveSnapshots));
    }
  }

  /** Mutable state for one CVE within a dependency. */
  static final class CveState {
    /**
     * First callsite hit, or {@code null} if not yet reached. AtomicReference ensures compareAndSet
     * atomicity so exactly one thread wins the "first hit" race.
     */
    final AtomicReference<ScaReachabilityHit> hitRef = new AtomicReference<>(null);
  }

  /** Immutable snapshot of a dependency's CVE state at drain time. */
  public static final class DependencySnapshot {
    public final String artifact;
    public final String version;

    /** All CVEs for this dependency: hit==null means known but not reached yet. */
    public final List<CveSnapshot> cves;

    DependencySnapshot(String artifact, String version, List<CveSnapshot> cves) {
      this.artifact = artifact;
      this.version = version;
      this.cves = cves;
    }
  }

  /** Snapshot of one CVE: hit is null if not yet reached. */
  public static final class CveSnapshot {
    public final String vulnId;
    public final ScaReachabilityHit hit; // null = reached:[]

    public CveSnapshot(String vulnId, ScaReachabilityHit hit) {
      this.vulnId = vulnId;
      this.hit = hit;
    }
  }
}
