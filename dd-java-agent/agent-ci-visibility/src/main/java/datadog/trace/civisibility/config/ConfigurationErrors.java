package datadog.trace.civisibility.config;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Tracks which CI Visibility backend requests failed. */
public class ConfigurationErrors {

  public static final ConfigurationErrors NONE =
      new ConfigurationErrors(false, false, false, false, false);

  private static final int SETTINGS_FLAG = 1;
  private static final int SKIPPABLE_TESTS_FLAG = 2;
  private static final int FLAKY_TESTS_FLAG = 4;
  private static final int KNOWN_TESTS_FLAG = 8;
  private static final int TEST_MANAGEMENT_TESTS_FLAG = 16;

  private final boolean settings;
  private final boolean skippableTests;
  private final boolean flakyTests;
  private final boolean knownTests;
  private final boolean testManagementTests;

  public ConfigurationErrors(
      boolean settings,
      boolean skippableTests,
      boolean flakyTests,
      boolean knownTests,
      boolean testManagementTests) {
    this.settings = settings;
    this.skippableTests = skippableTests;
    this.flakyTests = flakyTests;
    this.knownTests = knownTests;
    this.testManagementTests = testManagementTests;
  }

  public boolean hasAny() {
    return settings || skippableTests || flakyTests || knownTests || testManagementTests;
  }

  public void applyTags(AgentSpan span) {
    if (settings) {
      span.setTag(DDTags.CI_LIBRARY_CONFIGURATION_ERROR_SETTINGS, true);
    }
    if (skippableTests) {
      span.setTag(DDTags.CI_LIBRARY_CONFIGURATION_ERROR_SKIPPABLE_TESTS, true);
    }
    if (flakyTests) {
      span.setTag(DDTags.CI_LIBRARY_CONFIGURATION_ERROR_FLAKY_TESTS, true);
    }
    if (knownTests) {
      span.setTag(DDTags.CI_LIBRARY_CONFIGURATION_ERROR_KNOWN_TESTS, true);
    }
    if (testManagementTests) {
      span.setTag(DDTags.CI_LIBRARY_CONFIGURATION_ERROR_TEST_MANAGEMENT_TESTS, true);
    }
  }

  public static void serialize(Serializer s, ConfigurationErrors errors) {
    byte flags =
        (byte)
            ((errors.settings ? SETTINGS_FLAG : 0)
                | (errors.skippableTests ? SKIPPABLE_TESTS_FLAG : 0)
                | (errors.flakyTests ? FLAKY_TESTS_FLAG : 0)
                | (errors.knownTests ? KNOWN_TESTS_FLAG : 0)
                | (errors.testManagementTests ? TEST_MANAGEMENT_TESTS_FLAG : 0));
    s.write(flags);
  }

  public static ConfigurationErrors deserialize(ByteBuffer buffer) {
    byte flags = Serializer.readByte(buffer);
    if (flags == 0) {
      return NONE;
    }
    return new ConfigurationErrors(
        (flags & SETTINGS_FLAG) != 0,
        (flags & SKIPPABLE_TESTS_FLAG) != 0,
        (flags & FLAKY_TESTS_FLAG) != 0,
        (flags & KNOWN_TESTS_FLAG) != 0,
        (flags & TEST_MANAGEMENT_TESTS_FLAG) != 0);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ConfigurationErrors that = (ConfigurationErrors) o;
    return settings == that.settings
        && skippableTests == that.skippableTests
        && flakyTests == that.flakyTests
        && knownTests == that.knownTests
        && testManagementTests == that.testManagementTests;
  }

  @Override
  public int hashCode() {
    return Objects.hash(settings, skippableTests, flakyTests, knownTests, testManagementTests);
  }
}
