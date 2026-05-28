package datadog.trace.civisibility.config.api.dto.response;

import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.civisibility.config.TestSetting;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nullable;

public final class TestManagementTestsResponse {

  @Nullable public Map<String, Suites> modules;

  public Map<String, Suites> getModules() {
    return modules != null ? modules : Collections.emptyMap();
  }

  /**
   * Returns the contained tests grouped by {@link TestSetting} and module. The result always
   * contains an entry for {@link TestSetting#QUARANTINED}, {@link TestSetting#DISABLED} and {@link
   * TestSetting#ATTEMPT_TO_FIX} (the inner map may be empty for any of them).
   */
  public Map<TestSetting, Map<String, Collection<TestFQN>>> toTestFQNsBySetting() {
    Map<TestSetting, Map<String, Collection<TestFQN>>> result = new EnumMap<>(TestSetting.class);
    result.put(TestSetting.QUARANTINED, new HashMap<>());
    result.put(TestSetting.DISABLED, new HashMap<>());
    result.put(TestSetting.ATTEMPT_TO_FIX, new HashMap<>());

    for (Map.Entry<String, Suites> moduleEntry : getModules().entrySet()) {
      String moduleName = moduleEntry.getKey();
      for (Map.Entry<String, Tests> suiteEntry : moduleEntry.getValue().getSuites().entrySet()) {
        String suiteName = suiteEntry.getKey();
        for (Map.Entry<String, Properties> testEntry :
            suiteEntry.getValue().getTests().entrySet()) {
          String testName = testEntry.getKey();
          Properties properties = testEntry.getValue();
          TestFQN fqn = new TestFQN(suiteName, testName);
          if (properties.isQuarantined()) {
            result
                .get(TestSetting.QUARANTINED)
                .computeIfAbsent(moduleName, k -> new HashSet<>())
                .add(fqn);
          }
          if (properties.isDisabled()) {
            result
                .get(TestSetting.DISABLED)
                .computeIfAbsent(moduleName, k -> new HashSet<>())
                .add(fqn);
          }
          if (properties.isAttemptToFix()) {
            result
                .get(TestSetting.ATTEMPT_TO_FIX)
                .computeIfAbsent(moduleName, k -> new HashSet<>())
                .add(fqn);
          }
        }
      }
    }

    return result;
  }

  public int totalTestsCount() {
    int count = 0;
    for (Suites suites : getModules().values()) {
      for (Tests tests : suites.getSuites().values()) {
        count += tests.getTests().size();
      }
    }
    return count;
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
