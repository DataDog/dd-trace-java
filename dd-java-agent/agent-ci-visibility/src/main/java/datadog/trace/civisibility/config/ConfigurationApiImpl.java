package datadog.trace.civisibility.config;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import com.squareup.moshi.Types;
import datadog.communication.BackendApi;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.civisibility.config.Configurations;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageEnabled;
import datadog.trace.api.civisibility.telemetry.tag.EarlyFlakeDetectionEnabled;
import datadog.trace.api.civisibility.telemetry.tag.FlakyTestRetriesEnabled;
import datadog.trace.api.civisibility.telemetry.tag.ItrEnabled;
import datadog.trace.api.civisibility.telemetry.tag.ItrSkipEnabled;
import datadog.trace.api.civisibility.telemetry.tag.RequireGit;
import datadog.trace.civisibility.communication.TelemetryListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurationApiImpl implements ConfigurationApi {

  private static final Logger LOGGER = LoggerFactory.getLogger(ConfigurationApiImpl.class);

  private static final MediaType JSON = MediaType.get("application/json");

  private static final String SETTINGS_URI = "libraries/tests/services/setting";
  private static final String SKIPPABLE_TESTS_URI = "ci/tests/skippable";
  private static final String FLAKY_TESTS_URI = "ci/libraries/tests/flaky";
  private static final String KNOWN_TESTS_URI = "ci/libraries/tests";

  private final BackendApi backendApi;
  private final CiVisibilityMetricCollector metricCollector;
  private final Supplier<String> uuidGenerator;

  private final JsonAdapter<EnvelopeDto<TracerEnvironment>> requestAdapter;
  private final JsonAdapter<EnvelopeDto<CiVisibilitySettings>> settingsResponseAdapter;
  private final JsonAdapter<MultiEnvelopeDto<TestIdentifierJson>> testIdentifiersResponseAdapter;
  private final JsonAdapter<EnvelopeDto<KnownTestsDto>> testFullNamesResponseAdapter;

  public ConfigurationApiImpl(BackendApi backendApi, CiVisibilityMetricCollector metricCollector) {
    this(backendApi, metricCollector, () -> UUID.randomUUID().toString());
  }

  ConfigurationApiImpl(
      BackendApi backendApi,
      CiVisibilityMetricCollector metricCollector,
      Supplier<String> uuidGenerator) {
    this.backendApi = backendApi;
    this.metricCollector = metricCollector;
    this.uuidGenerator = uuidGenerator;

    Moshi moshi =
        new Moshi.Builder()
            .add(ConfigurationsJsonAdapter.INSTANCE)
            .add(CiVisibilitySettings.JsonAdapter.INSTANCE)
            .add(EarlyFlakeDetectionSettingsJsonAdapter.INSTANCE)
            .add(MetaDtoJsonAdapter.INSTANCE)
            .build();

    ParameterizedType requestType =
        Types.newParameterizedTypeWithOwner(
            ConfigurationApiImpl.class, EnvelopeDto.class, TracerEnvironment.class);
    requestAdapter = moshi.adapter(requestType);

    ParameterizedType settingsResponseType =
        Types.newParameterizedTypeWithOwner(
            ConfigurationApiImpl.class, EnvelopeDto.class, CiVisibilitySettings.class);
    settingsResponseAdapter = moshi.adapter(settingsResponseType);

    ParameterizedType testIdentifiersResponseType =
        Types.newParameterizedTypeWithOwner(
            ConfigurationApiImpl.class, MultiEnvelopeDto.class, TestIdentifierJson.class);
    testIdentifiersResponseAdapter = moshi.adapter(testIdentifiersResponseType);

    ParameterizedType testFullNamesResponseType =
        Types.newParameterizedTypeWithOwner(
            ConfigurationApiImpl.class, EnvelopeDto.class, KnownTestsDto.class);
    testFullNamesResponseAdapter = moshi.adapter(testFullNamesResponseType);
  }

  @Override
  public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException {
    String uuid = uuidGenerator.get();
    EnvelopeDto<TracerEnvironment> settingsRequest =
        new EnvelopeDto<>(
            new DataDto<>(uuid, "ci_app_test_service_libraries_settings", tracerEnvironment));
    String json = requestAdapter.toJson(settingsRequest);
    RequestBody requestBody = RequestBody.create(JSON, json);

    OkHttpUtils.CustomListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.GIT_REQUESTS_SETTINGS)
            .requestErrors(CiVisibilityCountMetric.GIT_REQUESTS_SETTINGS_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.GIT_REQUESTS_SETTINGS_MS)
            .build();

    CiVisibilitySettings settings =
        backendApi.post(
            SETTINGS_URI,
            requestBody,
            is -> settingsResponseAdapter.fromJson(Okio.buffer(Okio.source(is))).data.attributes,
            telemetryListener,
            false);

    metricCollector.add(
        CiVisibilityCountMetric.GIT_REQUESTS_SETTINGS_RESPONSE,
        1,
        settings.isItrEnabled() ? ItrEnabled.TRUE : null,
        settings.isTestsSkippingEnabled() ? ItrSkipEnabled.TRUE : null,
        settings.isCodeCoverageEnabled() ? CoverageEnabled.TRUE : null,
        settings.getEarlyFlakeDetectionSettings().isEnabled()
            ? EarlyFlakeDetectionEnabled.TRUE
            : null,
        settings.isFlakyTestRetriesEnabled() ? FlakyTestRetriesEnabled.TRUE : null,
        settings.isGitUploadRequired() ? RequireGit.TRUE : null);

    return settings;
  }

  @Override
  public SkippableTests getSkippableTests(TracerEnvironment tracerEnvironment) throws IOException {
    OkHttpUtils.CustomListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.ITR_SKIPPABLE_TESTS_REQUEST)
            .requestErrors(CiVisibilityCountMetric.ITR_SKIPPABLE_TESTS_REQUEST_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.ITR_SKIPPABLE_TESTS_REQUEST_MS)
            .responseBytes(CiVisibilityDistributionMetric.ITR_SKIPPABLE_TESTS_RESPONSE_BYTES)
            .build();

    String uuid = uuidGenerator.get();
    EnvelopeDto<TracerEnvironment> request =
        new EnvelopeDto<>(new DataDto<>(uuid, "test_params", tracerEnvironment));
    String json = requestAdapter.toJson(request);
    RequestBody requestBody = RequestBody.create(JSON, json);
    MultiEnvelopeDto<TestIdentifierJson> response =
        backendApi.post(
            SKIPPABLE_TESTS_URI,
            requestBody,
            is -> testIdentifiersResponseAdapter.fromJson(Okio.buffer(Okio.source(is))),
            telemetryListener,
            false);

    Configurations requestConf = tracerEnvironment.getConfigurations();

    Map<String, Map<TestIdentifier, TestMetadata>> testIdentifiersByModule = new HashMap<>();
    for (DataDto<TestIdentifierJson> dataDto : response.data) {
      TestIdentifierJson testIdentifierJson = dataDto.getAttributes();
      Configurations conf = testIdentifierJson.getConfigurations();
      String moduleName =
          (conf != null && conf.getTestBundle() != null ? conf : requestConf).getTestBundle();
      testIdentifiersByModule
          .computeIfAbsent(moduleName, k -> new HashMap<>())
          .put(testIdentifierJson.toTestIdentifier(), testIdentifierJson.toTestMetadata());
    }

    metricCollector.add(
        CiVisibilityCountMetric.ITR_SKIPPABLE_TESTS_RESPONSE_TESTS, response.data.size());

    String correlationId = response.meta != null ? response.meta.correlation_id : null;
    Map<String, BitSet> coveredLinesByRelativeSourcePath =
        response.meta != null ? response.meta.coverage : null;
    return new SkippableTests(
        correlationId, testIdentifiersByModule, coveredLinesByRelativeSourcePath);
  }

  @Override
  public Map<String, Collection<TestIdentifier>> getFlakyTestsByModule(
      TracerEnvironment tracerEnvironment) throws IOException {
    OkHttpUtils.CustomListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.FLAKY_TESTS_REQUEST)
            .requestErrors(CiVisibilityCountMetric.FLAKY_TESTS_REQUEST_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.FLAKY_TESTS_REQUEST_MS)
            .responseBytes(CiVisibilityDistributionMetric.FLAKY_TESTS_RESPONSE_BYTES)
            .build();

    String uuid = uuidGenerator.get();
    EnvelopeDto<TracerEnvironment> request =
        new EnvelopeDto<>(
            new DataDto<>(uuid, "flaky_test_from_libraries_params", tracerEnvironment));
    String json = requestAdapter.toJson(request);
    RequestBody requestBody = RequestBody.create(JSON, json);
    Collection<DataDto<TestIdentifierJson>> response =
        backendApi.post(
            FLAKY_TESTS_URI,
            requestBody,
            is -> testIdentifiersResponseAdapter.fromJson(Okio.buffer(Okio.source(is))).data,
            telemetryListener,
            false);

    LOGGER.debug("Received {} flaky tests in total", response.size());

    Configurations requestConf = tracerEnvironment.getConfigurations();

    int flakyTestsCount = 0;
    Map<String, Collection<TestIdentifier>> testIdentifiers = new HashMap<>();
    for (DataDto<TestIdentifierJson> dataDto : response) {
      TestIdentifierJson testIdentifierJson = dataDto.getAttributes();
      Configurations conf = testIdentifierJson.getConfigurations();
      String moduleName =
          (conf != null && conf.getTestBundle() != null ? conf : requestConf).getTestBundle();
      testIdentifiers
          .computeIfAbsent(moduleName, k -> new HashSet<>())
          .add(testIdentifierJson.toTestIdentifier());
      flakyTestsCount++;
    }

    metricCollector.add(CiVisibilityDistributionMetric.FLAKY_TESTS_RESPONSE_TESTS, flakyTestsCount);
    return testIdentifiers;
  }

  @Override
  public Map<String, Collection<TestIdentifier>> getKnownTestsByModule(
      TracerEnvironment tracerEnvironment) throws IOException {
    OkHttpUtils.CustomListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.EFD_REQUEST)
            .requestErrors(CiVisibilityCountMetric.EFD_REQUEST_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.EFD_REQUEST_MS)
            .responseBytes(CiVisibilityDistributionMetric.EFD_RESPONSE_BYTES)
            .build();

    String uuid = uuidGenerator.get();
    EnvelopeDto<TracerEnvironment> request =
        new EnvelopeDto<>(new DataDto<>(uuid, "ci_app_libraries_tests_request", tracerEnvironment));
    String json = requestAdapter.toJson(request);
    RequestBody requestBody = RequestBody.create(JSON, json);
    KnownTestsDto knownTests =
        backendApi.post(
            KNOWN_TESTS_URI,
            requestBody,
            is ->
                testFullNamesResponseAdapter.fromJson(Okio.buffer(Okio.source(is))).data.attributes,
            telemetryListener,
            false);

    return parseTestIdentifiers(knownTests);
  }

  private Map<String, Collection<TestIdentifier>> parseTestIdentifiers(KnownTestsDto knownTests) {
    int knownTestsCount = 0;

    Map<String, Collection<TestIdentifier>> testIdentifiers = new HashMap<>();
    for (Map.Entry<String, Map<String, List<String>>> e : knownTests.tests.entrySet()) {
      String moduleName = e.getKey();
      Map<String, List<String>> testsBySuiteName = e.getValue();

      for (Map.Entry<String, List<String>> se : testsBySuiteName.entrySet()) {
        String suiteName = se.getKey();
        List<String> testNames = se.getValue();
        knownTestsCount += testNames.size();

        for (String testName : testNames) {
          testIdentifiers
              .computeIfAbsent(moduleName, k -> new HashSet<>())
              .add(new TestIdentifier(suiteName, testName, null));
        }
      }
    }

    LOGGER.debug("Received {} known tests in total", knownTestsCount);
    metricCollector.add(CiVisibilityDistributionMetric.EFD_RESPONSE_TESTS, knownTestsCount);
    return testIdentifiers;
  }

  private static final class EnvelopeDto<T> {
    private final DataDto<T> data;

    private EnvelopeDto(DataDto<T> data) {
      this.data = data;
    }
  }

  private static final class MultiEnvelopeDto<T> {
    private final Collection<DataDto<T>> data;
    private final @Nullable MetaDto meta;

    private MultiEnvelopeDto(Collection<DataDto<T>> data, MetaDto meta) {
      this.data = data;
      this.meta = meta;
    }
  }

  private static final class DataDto<T> {
    private final String id;
    private final String type;
    private final T attributes;

    private DataDto(String id, String type, T attributes) {
      this.id = id;
      this.type = type;
      this.attributes = attributes;
    }

    public T getAttributes() {
      return attributes;
    }
  }

  private static final class MetaDto {
    private final String correlation_id;
    private final Map<String, BitSet> coverage;

    private MetaDto(String correlation_id, Map<String, BitSet> coverage) {
      this.correlation_id = correlation_id;
      this.coverage = coverage;
    }
  }

  private static final class MetaDtoJsonAdapter {

    private static final MetaDtoJsonAdapter INSTANCE = new MetaDtoJsonAdapter();

    @FromJson
    public MetaDto fromJson(Map<String, Object> json) {
      if (json == null) {
        return null;
      }

      Map<String, BitSet> coverage;
      Map<String, String> encodedCoverage = (Map<String, String>) json.get("coverage");
      if (encodedCoverage != null) {
        coverage = new HashMap<>();
        for (Map.Entry<String, String> e : encodedCoverage.entrySet()) {
          String relativeSourceFilePath = e.getKey();
          String normalizedSourceFilePath =
              relativeSourceFilePath.startsWith(File.separator)
                  ? relativeSourceFilePath.substring(1)
                  : relativeSourceFilePath;
          byte[] decodedLines = Base64.getDecoder().decode(e.getValue());
          coverage.put(normalizedSourceFilePath, BitSet.valueOf(decodedLines));
        }
      } else {
        coverage = null;
      }

      return new MetaDto((String) json.get("correlation_id"), coverage);
    }

    @ToJson
    public Map<String, Object> toJson(MetaDto metaDto) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class KnownTestsDto {
    private final Map<String, Map<String, List<String>>> tests;

    private KnownTestsDto(Map<String, Map<String, List<String>>> tests) {
      this.tests = tests;
    }
  }
}
