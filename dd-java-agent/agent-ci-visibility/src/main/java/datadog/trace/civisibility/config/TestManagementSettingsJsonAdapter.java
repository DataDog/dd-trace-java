package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;

import java.util.Map;

public class TestManagementSettingsJsonAdapter {
  public static final TestManagementSettingsJsonAdapter INSTANCE = new TestManagementSettingsJsonAdapter();

  @FromJson
  public TestManagementSettings fromJson(Map<String, Object> json) {
    if (json == null) {
      return TestManagementSettings.DEFAULT;
    }

    Boolean enabled = (Boolean) json.get("enabled");
    Double attemptToFixRetries = (Double) json.get("attempt_to_fix_retries");

    return new TestManagementSettings(
        enabled != null ? enabled : false,
        attemptToFixRetries != null ? attemptToFixRetries.intValue() : -1
    );
  }
}
