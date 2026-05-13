package datadog.telemetry.sca;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.telemetry.ScaReachabilityCollector;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drains the {@link ScaReachabilityCollector} on each telemetry heartbeat and reports reachability
 * hits as {@code app-dependencies-loaded} entries with {@code metadata} of type {@code
 * "reachability"}.
 *
 * <p>Hits are grouped by {@code (artifact, version)} so that multiple CVEs affecting the same
 * library version produce a single dependency entry with multiple metadata values, matching the RFC
 * payload format.
 *
 * <p>Registered in {@link datadog.telemetry.TelemetrySystem} when {@code DD_APPSEC_SCA_ENABLED} is
 * true.
 */
public final class ScaReachabilityPeriodicAction
    implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(TelemetryService telService) {
    List<ScaReachabilityHit> hits = ScaReachabilityCollector.INSTANCE.drain();
    if (hits.isEmpty()) {
      return;
    }

    // Group hits by (artifact, version) — multiple CVEs for the same dep go in one entry.
    Map<String, List<ScaReachabilityHit>> byArtifactVersion = new LinkedHashMap<>();
    for (ScaReachabilityHit hit : hits) {
      String key = hit.artifact() + "@" + hit.version();
      byArtifactVersion.computeIfAbsent(key, k -> new ArrayList<>()).add(hit);
    }

    for (Map.Entry<String, List<ScaReachabilityHit>> entry : byArtifactVersion.entrySet()) {
      List<ScaReachabilityHit> group = entry.getValue();
      ScaReachabilityHit first = group.get(0);

      // Build one stringified JSON metadata value per CVE in this group.
      List<String> metadataValues = new ArrayList<>(group.size());
      for (ScaReachabilityHit hit : group) {
        metadataValues.add(buildMetadataValue(hit));
      }

      Dependency dep =
          new Dependency(first.artifact(), first.version(), null, null, metadataValues);
      telService.addDependency(dep);
    }
  }

  /**
   * Builds the stringified JSON value for one reachability hit, per RFC:
   *
   * <pre>{@code {"id":"GHSA-xxx","reached":[{"path":"com.foo.Bar","symbol":"<clinit>","line":1}]}}
   * </pre>
   *
   * <p>For class-level hits, {@code symbol} is {@code "<clinit>"} and {@code line} is {@code 1}
   * (placeholder). For method-level hits, {@code symbol} is the actual method name and {@code line}
   * is the first line of the method definition.
   */
  static String buildMetadataValue(ScaReachabilityHit hit) {
    // Manual JSON construction — values are safe (GHSA IDs, FQN class names, and method names
    // contain no quotes or characters that require JSON escaping).
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
