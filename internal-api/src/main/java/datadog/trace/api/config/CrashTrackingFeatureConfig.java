package datadog.trace.api.config;

import static datadog.trace.api.DDTags.HOST_TAG;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.SERVICE_TAG;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_AGENTLESS_DEFAULT;
import static datadog.trace.api.config.CrashTrackingConfig.CRASH_TRACKING_TAGS;
import static datadog.trace.api.config.GeneralFeatureConfig.newHashMap;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.Map;

public class CrashTrackingFeatureConfig extends AbstractFeatureConfig {
  private final GeneralFeatureConfig generalConfig;
  private final TracerFeatureConfig tracerConfig;
  private final boolean crashTrackingAgentless;
  private final Map<String, String> crashTrackingTags;

  public CrashTrackingFeatureConfig(
      ConfigProvider configProvider,
      GeneralFeatureConfig generalConfig,
      TracerFeatureConfig tracerConfig) {
    super(configProvider);
    this.generalConfig = generalConfig;
    this.tracerConfig = tracerConfig;
    this.crashTrackingAgentless =
        configProvider.getBoolean(CRASH_TRACKING_AGENTLESS, CRASH_TRACKING_AGENTLESS_DEFAULT);
    this.crashTrackingTags = configProvider.getMergedMap(CRASH_TRACKING_TAGS);
  }

  public boolean isCrashTrackingAgentless() {
    return this.crashTrackingAgentless;
  }

  public String getFinalCrashTrackingTelemetryUrl() {
    if (this.crashTrackingAgentless) {
      // when agentless crashTracking is turned on we send directly to our intake
      return "https://all-http-intake.logs."
          + this.generalConfig.getSite()
          + "/api/v2/apmtelemetry";
    } else {
      // when agentless are not set we send to the dd trace agent running locally
      return "http://"
          + this.tracerConfig.getAgentHost()
          + ":"
          + this.tracerConfig.getAgentPort()
          + "/telemetry/proxy/api/v2/apmtelemetry";
    }
  }

  public Map<String, String> getMergedCrashTrackingTags() {
    final Map<String, String> runtimeTags = this.generalConfig.getRuntimeTags();
    final Map<String, String> globalTags = this.generalConfig.getGlobalTags();
    final String host = this.generalConfig.getHostName();
    final Map<String, String> result =
        newHashMap(
            globalTags.size()
                + this.crashTrackingTags.size()
                + runtimeTags.size()
                + 3 /* for serviceName and host and language */);
    result.put(HOST_TAG, host); // Host goes first to allow to override it
    result.putAll(globalTags);
    result.putAll(this.crashTrackingTags);
    result.putAll(runtimeTags);
    // service name set here instead of getRuntimeTags because apm already manages the service tag
    // and may choose to override it.
    result.put(SERVICE_TAG, this.generalConfig.getServiceName());
    result.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    return Collections.unmodifiableMap(result);
  }

  @Override
  public String toString() {
    return "CrashTrackingFeatureConfig{"
        + "generalConfig="
        + this.generalConfig
        + ", tracerConfig="
        + this.tracerConfig
        + ", crashTrackingAgentless="
        + this.crashTrackingAgentless
        + ", crashTrackingTags="
        + this.crashTrackingTags
        + '}';
  }
}
