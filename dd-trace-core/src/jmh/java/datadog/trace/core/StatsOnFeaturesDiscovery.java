package datadog.trace.core;

import static datadog.trace.api.ProtocolVersion.V0_4;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.metrics.api.Monitoring;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Feature discovery that reports an agent supporting client-side stats, with a realistic peer-tag
 * list, so a benchmark tracer computes stats on the finishing thread as it does in production.
 * Nothing is discovered over the network.
 */
final class StatsOnFeaturesDiscovery extends DDAgentFeaturesDiscovery {
  /** A subset of the peer tags the agent advertises, covering the benchmark's spans. */
  private static final Set<String> PEER_TAGS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              Arrays.asList(
                  "peer.hostname",
                  "peer.service",
                  "db.instance",
                  "db.system",
                  "out.host",
                  "network.destination.name",
                  "server.address")));

  StatsOnFeaturesDiscovery() {
    super(null, Monitoring.DISABLED, null, V0_4, true, false);
  }

  @Override
  public void discover() {}

  @Override
  public boolean supportsMetrics() {
    return true;
  }

  @Override
  public Set<String> peerTags() {
    return PEER_TAGS;
  }
}
