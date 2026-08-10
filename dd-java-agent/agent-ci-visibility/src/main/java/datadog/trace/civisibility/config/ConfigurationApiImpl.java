package datadog.trace.civisibility.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.communication.BackendApi;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.civisibility.telemetry.CiVisibilityCountMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityDistributionMetric;
import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.CoverageEnabled;
import datadog.trace.api.civisibility.telemetry.tag.EarlyFlakeDetectionEnabled;
import datadog.trace.api.civisibility.telemetry.tag.FailedTestReplayEnabled;
import datadog.trace.api.civisibility.telemetry.tag.FlakyTestRetriesEnabled;
import datadog.trace.api.civisibility.telemetry.tag.ImpactedTestsDetectionEnabled;
import datadog.trace.api.civisibility.telemetry.tag.ItrEnabled;
import datadog.trace.api.civisibility.telemetry.tag.ItrSkipEnabled;
import datadog.trace.api.civisibility.telemetry.tag.KnownTestsEnabled;
import datadog.trace.api.civisibility.telemetry.tag.RequireGit;
import datadog.trace.api.civisibility.telemetry.tag.TestManagementEnabled;
import datadog.trace.civisibility.communication.TelemetryListener;
import datadog.trace.civisibility.config.api.dto.ConfigurationApiMoshi;
import datadog.trace.civisibility.config.api.dto.Data;
import datadog.trace.civisibility.config.api.dto.Envelope;
import datadog.trace.civisibility.config.api.dto.MultiEnvelope;
import datadog.trace.civisibility.config.api.dto.request.KnownTestsRequest;
import datadog.trace.civisibility.config.api.dto.request.TestManagementRequest;
import datadog.trace.civisibility.config.api.dto.request.TracerEnvironment;
import datadog.trace.civisibility.config.api.dto.response.KnownTestsResponse;
import datadog.trace.civisibility.config.api.dto.response.TestIdentifierJson;
import datadog.trace.civisibility.config.api.dto.response.TestManagementTestsResponse;
import datadog.trace.util.RandomUtils;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private static final String TEST_MANAGEMENT_TESTS_URI = "test/libraries/test-management/tests";

  private final BackendApi backendApi;
  private final CiVisibilityMetricCollector metricCollector;
  private final Supplier<String> uuidGenerator;

  private final JsonAdapter<Envelope<TracerEnvironment>> requestAdapter;
  private final JsonAdapter<Envelope<CiVisibilitySettings>> settingsResponseAdapter;
  private final JsonAdapter<MultiEnvelope<TestIdentifierJson>> testIdentifiersResponseAdapter;
  private final JsonAdapter<Envelope<KnownTestsRequest>> knownTestsRequestAdapter;
  private final JsonAdapter<Envelope<KnownTestsResponse>> testFullNamesResponseAdapter;
  private final JsonAdapter<Envelope<TestManagementRequest>> testManagementRequestAdapter;
  private final JsonAdapter<Envelope<TestManagementTestsResponse>>
      testManagementTestsResponseAdapter;

  public ConfigurationApiImpl(BackendApi backendApi, CiVisibilityMetricCollector metricCollector) {
    this(backendApi, metricCollector, () -> RandomUtils.randomUUID().toString());
  }

  ConfigurationApiImpl(
      BackendApi backendApi,
      CiVisibilityMetricCollector metricCollector,
      Supplier<String> uuidGenerator) {
    this.backendApi = backendApi;
    this.metricCollector = metricCollector;
    this.uuidGenerator = uuidGenerator;

    Moshi moshi = ConfigurationApiMoshi.create();
    requestAdapter = moshi.adapter(envelopeOf(TracerEnvironment.class));
    settingsResponseAdapter = moshi.adapter(envelopeOf(CiVisibilitySettings.class));
    testIdentifiersResponseAdapter = moshi.adapter(multiEnvelopeOf(TestIdentifierJson.class));
    knownTestsRequestAdapter = moshi.adapter(envelopeOf(KnownTestsRequest.class));
    testFullNamesResponseAdapter = moshi.adapter(envelopeOf(KnownTestsResponse.class));
    testManagementRequestAdapter = moshi.adapter(envelopeOf(TestManagementRequest.class));
    testManagementTestsResponseAdapter =
        moshi.adapter(envelopeOf(TestManagementTestsResponse.class));
  }

  private static ParameterizedType envelopeOf(Class<?> attributesType) {
    return Types.newParameterizedType(Envelope.class, attributesType);
  }

  private static ParameterizedType multiEnvelopeOf(Class<?> attributesType) {
    return Types.newParameterizedType(MultiEnvelope.class, attributesType);
  }

  @Override
  public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException {
    String uuid = uuidGenerator.get();
    Envelope<TracerEnvironment> settingsRequest =
        new Envelope<>(
            new Data<>(uuid, "ci_app_test_service_libraries_settings", tracerEnvironment));
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
        settings.isKnownTestsEnabled() ? KnownTestsEnabled.TRUE : null,
        settings.isImpactedTestsDetectionEnabled() ? ImpactedTestsDetectionEnabled.TRUE : null,
        settings.getTestManagementSettings().isEnabled() ? TestManagementEnabled.TRUE : null,
        settings.isFailedTestReplayEnabled() ? FailedTestReplayEnabled.SettingsMetric.TRUE : null,
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
    Envelope<TracerEnvironment> request =
        new Envelope<>(new Data<>(uuid, "test_params", tracerEnvironment));
    String json = requestAdapter.toJson(request);
    RequestBody requestBody = RequestBody.create(JSON, json);
    MultiEnvelope<TestIdentifierJson> response =
        backendApi.post(
            SKIPPABLE_TESTS_URI,
            requestBody,
            is -> testIdentifiersResponseAdapter.fromJson(Okio.buffer(Okio.source(is))),
            telemetryListener,
            false);

    metricCollector.add(
        CiVisibilityCountMetric.ITR_SKIPPABLE_TESTS_RESPONSE_TESTS, response.data.size());

    return SkippableTests.from(response, tracerEnvironment);
  }

  @Override
  public Map<String, Collection<TestFQN>> getFlakyTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException {
    OkHttpUtils.CustomListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.FLAKY_TESTS_REQUEST)
            .requestErrors(CiVisibilityCountMetric.FLAKY_TESTS_REQUEST_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.FLAKY_TESTS_REQUEST_MS)
            .responseBytes(CiVisibilityDistributionMetric.FLAKY_TESTS_RESPONSE_BYTES)
            .build();

    String uuid = uuidGenerator.get();
    Envelope<TracerEnvironment> request =
        new Envelope<>(new Data<>(uuid, "flaky_test_from_libraries_params", tracerEnvironment));
    String json = requestAdapter.toJson(request);
    RequestBody requestBody = RequestBody.create(JSON, json);
    Collection<Data<TestIdentifierJson>> response =
        backendApi.post(
            FLAKY_TESTS_URI,
            requestBody,
            is -> testIdentifiersResponseAdapter.fromJson(Okio.buffer(Okio.source(is))).data,
            telemetryListener,
            false);

    LOGGER.debug("Received {} flaky tests in total", response.size());

    Map<String, Collection<TestFQN>> testsByModule =
        TestIdentifierJson.toTestFQNsByModule(response, tracerEnvironment);
    int flakyTestsCount = testsByModule.values().stream().mapToInt(Collection::size).sum();
    metricCollector.add(CiVisibilityDistributionMetric.FLAKY_TESTS_RESPONSE_TESTS, flakyTestsCount);
    return testsByModule;
  }

  @Nullable
  @Override
  public Map<String, Collection<TestFQN>> getKnownTestsByModule(TracerEnvironment tracerEnvironment)
      throws IOException {
    OkHttpUtils.CustomListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.KNOWN_TESTS_REQUEST)
            .requestErrors(CiVisibilityCountMetric.KNOWN_TESTS_REQUEST_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.KNOWN_TESTS_REQUEST_MS)
            .responseBytes(CiVisibilityDistributionMetric.KNOWN_TESTS_RESPONSE_BYTES)
            .build();

    // Aggregate tests map across all pages: module -> suite -> tests
    Map<String, Map<String, List<String>>> aggregateTests = new HashMap<>();
    String pageState = null;
    int pageNumber = 0;

    do {
      pageNumber += 1;
      LOGGER.debug(
          "Fetching known tests page #{}{}", pageNumber, pageState != null ? " with cursor" : "");
      String uuid = uuidGenerator.get();
      KnownTestsRequest requestDto = new KnownTestsRequest(tracerEnvironment, pageState);
      Envelope<KnownTestsRequest> request =
          new Envelope<>(new Data<>(uuid, "ci_app_libraries_tests_request", requestDto));
      String json = knownTestsRequestAdapter.toJson(request);
      RequestBody requestBody = RequestBody.create(JSON, json);
      KnownTestsResponse knownTests =
          backendApi.post(
              KNOWN_TESTS_URI,
              requestBody,
              is ->
                  testFullNamesResponseAdapter.fromJson(Okio.buffer(Okio.source(is)))
                      .data
                      .attributes,
              telemetryListener,
              false);

      mergeKnownTests(aggregateTests, knownTests.tests);

      Integer pageSize = knownTests.getPageSize();
      if (pageSize != null) {
        LOGGER.debug("Received page #{} of size {} for known tests", pageNumber, pageSize);
      } else {
        LOGGER.debug("Received page #{} for known tests", pageNumber);
      }

      pageState = knownTests.hasNextPage() ? knownTests.getNextPageCursor() : null;
    } while (pageState != null);

    LOGGER.debug("Finished fetching known tests after {} page(s)", pageNumber);

    Map<String, Collection<TestFQN>> testsByModule =
        KnownTestsResponse.toTestFQNsByModule(aggregateTests);
    int knownTestsCount =
        testsByModule != null
            ? testsByModule.values().stream().mapToInt(Collection::size).sum()
            : 0;
    LOGGER.debug("Received {} known tests in total", knownTestsCount);
    metricCollector.add(CiVisibilityDistributionMetric.KNOWN_TESTS_RESPONSE_TESTS, knownTestsCount);
    // returning null disables features that rely on known tests; this is intentional on the very
    // first execution for a repository, when we want to seed the backend with the initial set.
    return testsByModule;
  }

  private void mergeKnownTests(
      Map<String, Map<String, List<String>>> aggregate,
      Map<String, Map<String, List<String>>> page) {
    if (page == null) {
      return;
    }
    for (Map.Entry<String, Map<String, List<String>>> moduleEntry : page.entrySet()) {
      String moduleName = moduleEntry.getKey();
      Map<String, List<String>> pageSuites = moduleEntry.getValue();

      Map<String, List<String>> aggregateSuites =
          aggregate.computeIfAbsent(moduleName, k -> new HashMap<>());

      for (Map.Entry<String, List<String>> suiteEntry : pageSuites.entrySet()) {
        String suiteName = suiteEntry.getKey();
        List<String> pageTests = suiteEntry.getValue();

        aggregateSuites.merge(
            suiteName,
            pageTests,
            (existingTests, newTests) -> {
              existingTests.addAll(newTests);
              return existingTests;
            });
      }
    }
  }

  @Override
  public Map<TestSetting, Map<String, Collection<TestFQN>>> getTestManagementTestsByModule(
      TracerEnvironment tracerEnvironment, String commitSha, String commitMessage)
      throws IOException {
    OkHttpUtils.CustomListener telemetryListener =
        new TelemetryListener.Builder(metricCollector)
            .requestCount(CiVisibilityCountMetric.TEST_MANAGEMENT_TESTS_REQUEST)
            .requestErrors(CiVisibilityCountMetric.TEST_MANAGEMENT_TESTS_REQUEST_ERRORS)
            .requestDuration(CiVisibilityDistributionMetric.TEST_MANAGEMENT_TESTS_REQUEST_MS)
            .responseBytes(CiVisibilityDistributionMetric.TEST_MANAGEMENT_TESTS_RESPONSE_BYTES)
            .build();

    String uuid = uuidGenerator.get();
    Envelope<TestManagementRequest> request =
        new Envelope<>(
            new Data<>(
                uuid,
                "ci_app_libraries_tests_request",
                new TestManagementRequest(
                    tracerEnvironment.getRepositoryUrl(),
                    commitMessage,
                    tracerEnvironment.getConfigurations().getTestBundle(),
                    commitSha,
                    tracerEnvironment.getBranch())));
    String json = testManagementRequestAdapter.toJson(request);
    RequestBody requestBody = RequestBody.create(JSON, json);
    TestManagementTestsResponse response =
        backendApi.post(
            TEST_MANAGEMENT_TESTS_URI,
            requestBody,
            is ->
                testManagementTestsResponseAdapter.fromJson(Okio.buffer(Okio.source(is)))
                    .data
                    .attributes,
            telemetryListener,
            false);

    int testsCount = response.totalTestsCount();
    LOGGER.debug("Received {} test management tests in total", testsCount);
    metricCollector.add(
        CiVisibilityDistributionMetric.TEST_MANAGEMENT_TESTS_RESPONSE_TESTS, testsCount);

    return response.toTestFQNsBySetting();
  }
}
