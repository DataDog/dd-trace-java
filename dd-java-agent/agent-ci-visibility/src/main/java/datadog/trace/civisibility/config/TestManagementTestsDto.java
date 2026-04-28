package datadog.trace.civisibility.config;

import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;

final class TestManagementTestsDto {

  @Nullable Map<String, Suites> modules;

  Map<String, Suites> getModules() {
    return modules != null ? modules : Collections.emptyMap();
  }

  static final class Properties {
    @Nullable Map<String, Boolean> properties;

    boolean isQuarantined() {
      return properties != null
          && properties.getOrDefault(TestSetting.QUARANTINED.asString(), false);
    }

    boolean isDisabled() {
      return properties != null && properties.getOrDefault(TestSetting.DISABLED.asString(), false);
    }

    boolean isAttemptToFix() {
      return properties != null
          && properties.getOrDefault(TestSetting.ATTEMPT_TO_FIX.asString(), false);
    }
  }

  static final class Tests {
    @Nullable Map<String, Properties> tests;

    Map<String, Properties> getTests() {
      return tests != null ? tests : Collections.emptyMap();
    }
  }

  static final class Suites {
    @Nullable Map<String, Tests> suites;

    Map<String, Tests> getSuites() {
      return suites != null ? suites : Collections.emptyMap();
    }
  }
}
