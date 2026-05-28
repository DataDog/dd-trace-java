package datadog.trace.civisibility.config.api.dto;

import com.squareup.moshi.Moshi;
import datadog.trace.civisibility.config.CiVisibilitySettings;
import datadog.trace.civisibility.config.ConfigurationsJsonAdapter;
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings;
import datadog.trace.civisibility.config.TestManagementSettings;
import datadog.trace.civisibility.config.api.dto.response.Meta;

/**
 * Builds the shared Moshi instance used to (de)serialize the Configuration API wire DTOs. Both the
 * HTTP and file-based implementations rely on the same envelope structure, so they share the same
 * set of registered adapters.
 */
public final class ConfigurationApiMoshi {

  private ConfigurationApiMoshi() {}

  public static Moshi create() {
    return new Moshi.Builder()
        .add(ConfigurationsJsonAdapter.INSTANCE)
        .add(CiVisibilitySettings.JsonAdapter.INSTANCE)
        .add(EarlyFlakeDetectionSettings.JsonAdapter.INSTANCE)
        .add(TestManagementSettings.JsonAdapter.INSTANCE)
        .add(Meta.JsonAdapter.INSTANCE)
        .build();
  }
}
