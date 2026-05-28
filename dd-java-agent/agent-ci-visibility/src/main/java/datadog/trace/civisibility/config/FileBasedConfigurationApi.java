package datadog.trace.civisibility.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.civisibility.config.api.dto.ConfigurationApiMoshi;
import datadog.trace.civisibility.config.api.dto.Envelope;
import datadog.trace.civisibility.config.api.dto.MultiEnvelope;
import datadog.trace.civisibility.config.api.dto.request.TracerEnvironment;
import datadog.trace.civisibility.config.api.dto.response.KnownTestsResponse;
import datadog.trace.civisibility.config.api.dto.response.TestIdentifierJson;
import datadog.trace.civisibility.config.api.dto.response.TestManagementTestsResponse;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import javax.annotation.Nullable;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements {@link ConfigurationApi} by reading JSON files from disk instead of making HTTP
 * requests. Each file is expected to contain the same JSON envelope structure that the backend API
 * returns. Used in Bazel mode.
 */
public class FileBasedConfigurationApi implements ConfigurationApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(FileBasedConfigurationApi.class);

  @Nullable private final Path settingsPath;
  @Nullable private final Path skippableTestsPath;
  @Nullable private final Path flakyTestsPath;
  @Nullable private final Path knownTestsPath;
  @Nullable private final Path testManagementPath;

  private final JsonAdapter<Envelope<CiVisibilitySettings>> settingsAdapter;
  private final JsonAdapter<Envelope<KnownTestsResponse>> knownTestsAdapter;
  private final JsonAdapter<Envelope<TestManagementTestsResponse>> testManagementAdapter;
  private final JsonAdapter<MultiEnvelope<TestIdentifierJson>> testIdentifiersAdapter;

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

    Moshi moshi = ConfigurationApiMoshi.create();
    settingsAdapter = moshi.adapter(envelopeOf(CiVisibilitySettings.class));
    knownTestsAdapter = moshi.adapter(envelopeOf(KnownTestsResponse.class));
    testManagementAdapter = moshi.adapter(envelopeOf(TestManagementTestsResponse.class));
    testIdentifiersAdapter = moshi.adapter(multiEnvelopeOf(TestIdentifierJson.class));
  }

  private static ParameterizedType envelopeOf(Class<?> attributesType) {
    return Types.newParameterizedType(Envelope.class, attributesType);
  }

  private static ParameterizedType multiEnvelopeOf(Class<?> attributesType) {
    return Types.newParameterizedType(MultiEnvelope.class, attributesType);
  }

  @Override
  public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException {
    if (settingsPath == null) {
      LOGGER.debug("Settings file path not provided, returning defaults");
      return CiVisibilitySettings.DEFAULT;
    }
    LOGGER.debug("Reading settings from {}", settingsPath);
    Envelope<CiVisibilitySettings> envelope = readEnvelope(settingsPath, settingsAdapter);
    return envelope != null && envelope.data != null && envelope.data.attributes != null
        ? envelope.data.attributes
        : CiVisibilitySettings.DEFAULT;
  }

  @Override
  public SkippableTests getSkippableTests(TracerEnvironment tracerEnvironment) throws IOException {
    if (skippableTestsPath == null) {
      LOGGER.debug("Skippable tests file path not provided, returning empty");
      return SkippableTests.EMPTY;
    }
    LOGGER.debug("Reading skippable tests from {}", skippableTestsPath);
    MultiEnvelope<TestIdentifierJson> envelope =
        readEnvelope(skippableTestsPath, testIdentifiersAdapter);
    if (envelope == null || envelope.data == null) {
      return SkippableTests.EMPTY;
    }
    return SkippableTests.from(envelope, tracerEnvironment);
  }

  @Override
  public Map<String, Collection<TestFQN>> getFlakyTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException {
    if (flakyTestsPath == null) {
      LOGGER.debug("Flaky tests file path not provided, returning empty");
      return Collections.emptyMap();
    }
    LOGGER.debug("Reading flaky tests from {}", flakyTestsPath);
    MultiEnvelope<TestIdentifierJson> envelope =
        readEnvelope(flakyTestsPath, testIdentifiersAdapter);
    if (envelope == null || envelope.data == null) {
      return Collections.emptyMap();
    }
    Map<String, Collection<TestFQN>> result =
        TestIdentifierJson.toTestFQNsByModule(envelope.data, tracerEnvironment);
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
    Envelope<KnownTestsResponse> envelope = readEnvelope(knownTestsPath, knownTestsAdapter);
    if (envelope == null
        || envelope.data == null
        || envelope.data.attributes == null
        || envelope.data.attributes.tests == null) {
      return Collections.emptyMap();
    }
    Map<String, Collection<TestFQN>> result =
        KnownTestsResponse.toTestFQNsByModule(envelope.data.attributes.tests);
    LOGGER.debug(
        "Read {} known tests from file",
        result != null ? result.values().stream().mapToInt(Collection::size).sum() : 0);
    return result;
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
    Envelope<TestManagementTestsResponse> envelope =
        readEnvelope(testManagementPath, testManagementAdapter);
    if (envelope == null || envelope.data == null || envelope.data.attributes == null) {
      return Collections.emptyMap();
    }
    return envelope.data.attributes.toTestFQNsBySetting();
  }

  @Nullable
  private static <T> T readEnvelope(Path path, JsonAdapter<T> adapter) throws IOException {
    try (BufferedSource source = Okio.buffer(Okio.source(path))) {
      return adapter.fromJson(source);
    }
  }
}
