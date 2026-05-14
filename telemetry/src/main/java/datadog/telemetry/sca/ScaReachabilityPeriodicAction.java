package datadog.telemetry.sca;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.CveSnapshot;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry.DependencySnapshot;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports SCA Reachability state on each telemetry heartbeat, implementing the RFC stateful model:
 *
 * <ol>
 *   <li>When a CVE is first detected (class load): reports {@code metadata:
 *       [{"type":"reachability","value":"{\"id\":\"...\",\"reached\":[]}"}]} — signals the backend
 *       that SCA is monitoring this CVE even before any symbol is called.
 *   <li>When a vulnerable symbol is called: re-reports the dependency with ALL its CVEs, now
 *       including the callsite in {@code reached} for the CVE that was hit.
 *   <li>When nothing changes: reports {@code dependencies:[]} (empty heartbeat).
 * </ol>
 *
 * <p>The key invariant: whenever any CVE's state changes, ALL CVEs for the same dependency are
 * re-reported together so the backend always has a complete picture.
 *
 * <p>Registered in {@link datadog.telemetry.TelemetrySystem} when {@code DD_APPSEC_SCA_ENABLED} is
 * true.
 */
public final class ScaReachabilityPeriodicAction
    implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(TelemetryService telService) {
    // Trigger pending retransformations (method-level symbols on already-loaded classes, or
    // classes where JAR version resolution failed at load time and needs a retry).
    Runnable work = ScaReachabilityDependencyRegistry.INSTANCE.getPeriodicWorkCallback();
    if (work != null) {
      work.run();
    }

    List<DependencySnapshot> pending =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    if (pending.isEmpty()) {
      return;
    }

    for (DependencySnapshot dep : pending) {
      // Build one metadata entry per CVE — both those with and without callsite hits.
      // This ensures the backend always sees ALL CVEs for the dependency, not just new ones.
      List<String> metadataValues = new ArrayList<>(dep.cves.size());
      for (CveSnapshot cve : dep.cves) {
        metadataValues.add(buildMetadataValue(cve));
      }
      telService.addDependency(
          new Dependency(dep.artifact, dep.version, null, null, metadataValues));
    }
  }

  /**
   * Builds the stringified JSON value for one CVE snapshot, per RFC:
   *
   * <ul>
   *   <li>Not yet reached: {@code {"id":"GHSA-xxx","reached":[]}}
   *   <li>Reached: {@code
   *       {"id":"GHSA-xxx","reached":[{"path":"com.foo.Bar","symbol":"...","line":N}]}}
   * </ul>
   */
  static String buildMetadataValue(CveSnapshot cve) {
    ScaReachabilityHit hit = cve.hit;
    if (hit == null) {
      // CVE known but no callsite yet — signals "monitoring, not reached"
      return "{\"id\":\"" + cve.vulnId + "\",\"reached\":[]}";
    }
    // CVE has been reached — include the callsite
    return "{\"id\":\""
        + hit.vulnId()
        + "\",\"reached\":[{\"path\":\""
        + hit.className()
        + "\",\"symbol\":\""
        + hit.symbolName()
        + "\",\"line\":"
        + hit.line()
        + "}]}";
  }
}
