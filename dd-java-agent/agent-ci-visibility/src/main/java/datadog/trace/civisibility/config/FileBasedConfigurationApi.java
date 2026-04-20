package datadog.trace.civisibility.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.civisibility.config.Configurations;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@link ConfigurationApi} by reading JSON files from disk instead of making HTTP
 * requests. Each file is expected to contain the same JSON envelope structure that the backend API
 * returns.
 *
 * <p>Paths that are {@code null} are treated as absent — the corresponding method returns a safe
 * default (e.g. {@link CiVisibilitySettings#DEFAULT} or an empty collection).
 */
public class FileBasedConfigurationApi implements ConfigurationApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileBasedConfigurationApi.class);

  @Nullable private final Path settingsPath;
  @Nullable private final Path skippableTestsPath;
  @Nullable private final Path flakyTestsPath;
  @Nullable private final Path knownTestsPath;
  @Nullable private final Path testManagementPath;

  private final JsonAdapter<SettingsEnvelope> settingsAdapter;
  private final JsonAdapter<KnownTestsEnvelope> knownTestsAdapter;
  private final JsonAdapter<TestManagementEnvelope> testManagementAdapter;
  private final JsonAdapter<TestIdentifiersEnvelope> testIdentifiersAdapter;

  public FileBasedConfigurationApi(
      @Nullable Path settingsPath,
      @Nullable Path skippableTestsPath,
      @Nullable Path flakyTestsPath,
      @Nullable Path knownTestsPath,
      @Nullable Path testManagementPath) {
    this.settingsPath = settingsPath;
    this.skippableTestsPath = skippableTestsPath;
    this.flakyTestsPath = flakyTestsPath;
    this.knownTestsPath = knownTestsPath;
    this.testManagementPath = testManagementPath;

    Moshi moshi =
        new Moshi.Builder()
            .add(ConfigurationsJsonAdapter.INSTANCE)
            .add(CiVisibilitySettings.JsonAdapter.INSTANCE)
            .add(EarlyFlakeDetectionSettings.JsonAdapter.INSTANCE)
            .add(TestManagementSettings.JsonAdapter.INSTANCE)
            .add(MetaDto.JsonAdapter.INSTANCE)
            .build();

    settingsAdapter = moshi.adapter(SettingsEnvelope.class);
    knownTestsAdapter = moshi.adapter(KnownTestsEnvelope.class);
    testManagementAdapter = moshi.adapter(TestManagementEnvelope.class);

    ParameterizedType testIdentifiersType =
        Types.newParameterizedTypeWithOwner(
            FileBasedConfigurationApi.class, TestIdentifiersEnvelope.class);
    testIdentifiersAdapter = moshi.adapter(testIdentifiersType);
  }

  @Override
  public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException {
    if (settingsPath == null) {
      LOGGER.debug("Settings file path not provided, returning defaults");
      return CiVisibilitySettings.DEFAULT;
    }

    LOGGER.debug("Reading settings from {}", settingsPath);
    try (BufferedSource source = Okio.buffer(Okio.source(settingsPath))) {
      SettingsEnvelope envelope = settingsAdapter.fromJson(source);
      if (envelope != null && envelope.data != null && envelope.data.attributes != null) {
        return envelope.data.attributes;
      }
    }
    return CiVisibilitySettings.DEFAULT;
  }

  @Override
  public SkippableTests getSkippableTests(TracerEnvironment tracerEnvironment) throws IOException {
    if (skippableTestsPath == null) {
      LOGGER.debug("Skippable tests file path not provided, returning empty");
      return SkippableTests.EMPTY;
    }

    LOGGER.debug("Reading skippable tests from {}", skippableTestsPath);
    try (BufferedSource source = Okio.buffer(Okio.source(skippableTestsPath))) {
      TestIdentifiersEnvelope envelope = testIdentifiersAdapter.fromJson(source);
      if (envelope != null && envelope.data != null) {
        return toSkippableTests(envelope, tracerEnvironment);
      }
    }
    return SkippableTests.EMPTY;
  }

  private SkippableTests toSkippableTests(
      TestIdentifiersEnvelope envelope, TracerEnvironment tracerEnvironment) {
    Configurations requestConf = tracerEnvironment.getConfigurations();

    Map<String, Map<TestIdentifier, TestMetadata>> identifiersByModule = new HashMap<>();
    for (TestIdentifierDataDto dataDto : envelope.data) {
      TestIdentifierJson testIdJson = dataDto.attributes;
      if (testIdJson == null) {
        continue;
      }
      Configurations conf = testIdJson.getConfigurations();
      String moduleName =
          (conf != null && conf.getTestBundle() != null ? conf : requestConf).getTestBundle();
      identifiersByModule
          .computeIfAbsent(moduleName, k -> new HashMap<>())
          .put(testIdJson.toTestIdentifier(), testIdJson.toTestMetadata());
    }

    String correlationId = envelope.meta != null ? envelope.meta.correlationId : null;
    Map<String, BitSet> coverage =
        envelope.meta != null && envelope.meta.coverage != null
            ? envelope.meta.coverage
            : Collections.emptyMap();
    return new SkippableTests(correlationId, identifiersByModule, coverage);
  }

  @Override
  public Map<String, Collection<TestFQN>> getFlakyTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException {
    if (flakyTestsPath == null) {
      LOGGER.debug("Flaky tests file path not provided, returning empty");
      return Collections.emptyMap();
    }

    LOGGER.debug("Reading flaky tests from {}", flakyTestsPath);
    try (BufferedSource source = Okio.buffer(Okio.source(flakyTestsPath))) {
      TestIdentifiersEnvelope envelope = testIdentifiersAdapter.fromJson(source);
      if (envelope != null && envelope.data != null) {
        return toFlakyTestsByModule(envelope, tracerEnvironment);
      }
    }
    return Collections.emptyMap();
  }

  private Map<String, Collection<TestFQN>> toFlakyTestsByModule(
      TestIdentifiersEnvelope envelope, TracerEnvironment tracerEnvironment) {
    Configurations requestConf = tracerEnvironment.getConfigurations();

    Map<String, Collection<TestFQN>> result = new HashMap<>();
    for (TestIdentifierDataDto dataDto : envelope.data) {
      TestIdentifierJson testIdJson = dataDto.attributes;
      if (testIdJson == null) {
        continue;
      }
      Configurations conf = testIdJson.getConfigurations();
      String moduleName =
          (conf != null && conf.getTestBundle() != null ? conf : requestConf).getTestBundle();
      result
          .computeIfAbsent(moduleName, k -> new HashSet<>())
          .add(testIdJson.toTestIdentifier().toFQN());
    }
    LOGGER.debug(
        "Read {} flaky tests from file", result.values().stream().mapToInt(Collection::size).sum());
    return result;
  }

  @Nullable
  @Override
  public Map<String, Collection<TestFQN>> getKnownTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException {
    if (knownTestsPath == null) {
      LOGGER.debug("Known tests file path not provided, returning empty");
      return Collections.emptyMap();
    }

    LOGGER.debug("Reading known tests from {}", knownTestsPath);
    try (BufferedSource source = Okio.buffer(Okio.source(knownTestsPath))) {
      KnownTestsEnvelope envelope = knownTestsAdapter.fromJson(source);
      if (envelope != null
          && envelope.data != null
          && envelope.data.attributes != null
          && envelope.data.attributes.tests != null) {
        return parseKnownTests(envelope.data.attributes.tests);
      }
    }
    return Collections.emptyMap();
  }

  private Map<String, Collection<TestFQN>> parseKnownTests(
      Map<String, Map<String, List<String>>> testsMap) {
    int count = 0;
    Map<String, Collection<TestFQN>> result = new HashMap<>();
    for (Map.Entry<String, Map<String, List<String>>> moduleEntry : testsMap.entrySet()) {
      String moduleName = moduleEntry.getKey();
      for (Map.Entry<String, List<String>> suiteEntry : moduleEntry.getValue().entrySet()) {
        String suiteName = suiteEntry.getKey();
        for (String testName : suiteEntry.getValue()) {
          result
              .computeIfAbsent(moduleName, k -> new HashSet<>())
              .add(new TestFQN(suiteName, testName));
          count++;
        }
      }
    }
    LOGGER.debug("Read {} known tests from file", count);
    return count > 0 ? result : null;
  }

  @Override
  public Map<TestSetting, Map<String, Collection<TestFQN>>> getTestManagementTestsByModule(
      TracerEnvironment tracerEnvironment, String commitSha, String commitMessage)
      throws IOException {
    if (testManagementPath == null) {
      LOGGER.debug("Test management file path not provided, returning empty");
      return Collections.emptyMap();
    }

    LOGGER.debug("Reading test management data from {}", testManagementPath);
    try (BufferedSource source = Okio.buffer(Okio.source(testManagementPath))) {
      TestManagementEnvelope envelope = testManagementAdapter.fromJson(source);
      if (envelope != null && envelope.data != null && envelope.data.attributes != null) {
        return parseTestManagementTests(envelope.data.attributes);
      }
    }
    return Collections.emptyMap();
  }

  private Map<TestSetting, Map<String, Collection<TestFQN>>> parseTestManagementTests(
      TestManagementTestsDto dto) {
    Map<String, Collection<TestFQN>> quarantined = new HashMap<>();
    Map<String, Collection<TestFQN>> disabled = new HashMap<>();
    Map<String, Collection<TestFQN>> attemptToFix = new HashMap<>();

    for (Map.Entry<String, TestManagementTestsDto.Suites> moduleEntry :
        dto.getModules().entrySet()) {
      String moduleName = moduleEntry.getKey();
      Map<String, TestManagementTestsDto.Tests> suites = moduleEntry.getValue().getSuites();

      for (Map.Entry<String, TestManagementTestsDto.Tests> suiteEntry : suites.entrySet()) {
        String suiteName = suiteEntry.getKey();
        Map<String, TestManagementTestsDto.Properties> tests = suiteEntry.getValue().getTests();

        for (Map.Entry<String, TestManagementTestsDto.Properties> testEntry : tests.entrySet()) {
          String testName = testEntry.getKey();
          TestManagementTestsDto.Properties props = testEntry.getValue();
          TestFQN fqn = new TestFQN(suiteName, testName);

          if (props.isQuarantined()) {
            quarantined.computeIfAbsent(moduleName, k -> new HashSet<>()).add(fqn);
          }
          if (props.isDisabled()) {
            disabled.computeIfAbsent(moduleName, k -> new HashSet<>()).add(fqn);
          }
          if (props.isAttemptToFix()) {
            attemptToFix.computeIfAbsent(moduleName, k -> new HashSet<>()).add(fqn);
          }
        }
      }
    }

    Map<TestSetting, Map<String, Collection<TestFQN>>> result = new HashMap<>();
    result.put(TestSetting.QUARANTINED, quarantined);
    result.put(TestSetting.DISABLED, disabled);
    result.put(TestSetting.ATTEMPT_TO_FIX, attemptToFix);
    return result;
  }

  // --- DTOs ---

  // Settings envelope: { "data": { "attributes": CiVisibilitySettings } }
  static final class SettingsEnvelope {
    DataDto<CiVisibilitySettings> data;
  }

  // Known tests envelope: { "data": { "attributes": { "tests": ... } } }
  static final class KnownTestsEnvelope {
    DataDto<KnownTestsAttributes> data;
  }

  // Test management envelope: { "data": { "attributes": TestManagementTestsDto } }
  static final class TestManagementEnvelope {
    DataDto<TestManagementTestsDto> data;
  }

  // Shared single-item data wrapper
  static final class DataDto<T> {
    String id;
    String type;
    T attributes;
  }

  static final class KnownTestsAttributes {
    Map<String, Map<String, List<String>>> tests;
  }

  // Skippable/flaky tests envelope: { "data": [...], "meta": { ... } }
  static final class TestIdentifiersEnvelope {
    Collection<TestIdentifierDataDto> data;
    @Nullable MetaDto meta;
  }

  static final class TestIdentifierDataDto {
    String id;
    String type;
    TestIdentifierJson attributes;
  }
}
