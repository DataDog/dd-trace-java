package datadog.communication.ddagent;

import static datadog.trace.api.ProtocolVersion.V0_4;

import datadog.metrics.api.Monitoring;

/**
 * No-op {@link DDAgentFeaturesDiscovery} used when the tracer must not probe the agent — for
 * example, in CI Visibility hermetic Bazel runs where traces are written to local files. Skips
 * discovery and reports nothing as supported, so callers get safe default values without any
 * network I/O.
 */
public class NoopFeaturesDiscovery extends DDAgentFeaturesDiscovery {
  public static final NoopFeaturesDiscovery INSTANCE = new NoopFeaturesDiscovery();

  private NoopFeaturesDiscovery() {
    super(null, Monitoring.DISABLED, null, V0_4, false, false);
  }

  @Override
  public void discover() {}

  @Override
  public void discoverIfOutdated() {}
}
