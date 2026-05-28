package datadog.trace.civisibility.config.api.dto.response;

import datadog.trace.civisibility.config.TestSetting;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;

public final class TestManagementTestsResponse {

  @Nullable public Map<String, Suites> modules;

  public Map<String, Suites> getModules() {
    return modules != null ? modules : Collections.emptyMap();
  }

  public static final class Properties {
    @Nullable public Map<String, Boolean> properties;

    public boolean isQuarantined() {
      return properties != null
          && properties.getOrDefault(TestSetting.QUARANTINED.asString(), false);
    }

    public boolean isDisabled() {
      return properties != null && properties.getOrDefault(TestSetting.DISABLED.asString(), false);
    }

    public boolean isAttemptToFix() {
      return properties != null
          && properties.getOrDefault(TestSetting.ATTEMPT_TO_FIX.asString(), false);
    }
  }

  public static final class Tests {
    @Nullable public Map<String, Properties> tests;

    public Map<String, Properties> getTests() {
      return tests != null ? tests : Collections.emptyMap();
    }
  }

  public static final class Suites {
    @Nullable public Map<String, Tests> suites;

    public Map<String, Tests> getSuites() {
      return suites != null ? suites : Collections.emptyMap();
    }
  }
}
