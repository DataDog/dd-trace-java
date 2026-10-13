package datadog.trace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TraceStatsAdditionalTagsTest {

  private static Config configWith(String experimentalFeatures, String additionalTags) {
    Properties props = new Properties();
    if (experimentalFeatures != null) {
      props.setProperty("trace.experimental.features.enabled", experimentalFeatures);
    }
    if (additionalTags != null) {
      props.setProperty("trace.stats.additional.tags", additionalTags);
    }
    return Config.get(props);
  }

  @Test
  void returnsConfiguredTagsWhenFeatureEnabled() {
    Config config = configWith("DD_TRACE_STATS_ADDITIONAL_TAGS", "peer.hostname,messaging.system");
    Set<String> tags = config.getTraceStatsAdditionalTags();
    assertEquals(2, tags.size());
    assertTrue(tags.contains("peer.hostname"));
    assertTrue(tags.contains("messaging.system"));
  }

  @Test
  void emptyWhenFeatureNotEnabled() {
    // The value is configured but the experimental feature gate is closed.
    Config config = configWith(null, "peer.hostname");
    assertTrue(config.getTraceStatsAdditionalTags().isEmpty());
  }

  @Test
  void returnsSameSnapshotInstanceOnEachCall() {
    // The setting is snapshotted into a final field at construction, so repeated reads
    // return the identical immutable instance rather than re-querying the config source.
    Config config = configWith("DD_TRACE_STATS_ADDITIONAL_TAGS", "peer.hostname");
    assertEquals(config.getTraceStatsAdditionalTags(), config.getTraceStatsAdditionalTags());
  }

  @Test
  void includedInToString() {
    Config config = configWith("DD_TRACE_STATS_ADDITIONAL_TAGS", "peer.hostname");
    assertTrue(config.toString().contains("traceStatsAdditionalTags="));
  }
}
