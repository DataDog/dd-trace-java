package datadog.trace.civisibility.config;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.civisibility.communication.BackendApi;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Okio;

public class ConfigurationApiImpl implements ConfigurationApi {

  private static final MediaType JSON = MediaType.get("application/json");

  private static final String SETTINGS_URI = "libraries/tests/services/setting";
  private static final String SKIPPABLE_TESTS_URI = "ci/tests/skippable";
  private static final String FLAKY_TESTS_URI = "ci/libraries/tests/flaky";

  private final BackendApi backendApi;
  private final Supplier<String> uuidGenerator;

  private final JsonAdapter<EnvelopeDto<TracerEnvironment>> requestAdapter;
  private final JsonAdapter<EnvelopeDto<CiVisibilitySettings>> settingsResponseAdapter;
  private final JsonAdapter<MultiEnvelopeDto<TestIdentifier>> testIdentifiersResponseAdapter;

  public ConfigurationApiImpl(BackendApi backendApi) {
    this(backendApi, () -> UUID.randomUUID().toString());
  }

  ConfigurationApiImpl(BackendApi backendApi, Supplier<String> uuidGenerator) {
    this.backendApi = backendApi;
    this.uuidGenerator = uuidGenerator;

    Moshi moshi =
        new Moshi.Builder().add(new ConfigurationsJson.ConfigurationsJsonAdapter()).build();

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
            ConfigurationApiImpl.class, MultiEnvelopeDto.class, TestIdentifier.class);
    testIdentifiersResponseAdapter = moshi.adapter(testIdentifiersResponseType);
  }

  @Override
  public CiVisibilitySettings getSettings(TracerEnvironment tracerEnvironment) throws IOException {
    String uuid = uuidGenerator.get();
    EnvelopeDto<TracerEnvironment> settingsRequest =
        new EnvelopeDto<>(
            new DataDto<>(uuid, "ci_app_test_service_libraries_settings", tracerEnvironment));
    String json = requestAdapter.toJson(settingsRequest);
    RequestBody requestBody = RequestBody.create(JSON, json);
    return backendApi.post(
        SETTINGS_URI,
        requestBody,
        is -> settingsResponseAdapter.fromJson(Okio.buffer(Okio.source(is))).data.attributes);
  }

  @Override
  public Collection<TestIdentifier> getSkippableTests(TracerEnvironment tracerEnvironment)
      throws IOException {
    return getTestsData(SKIPPABLE_TESTS_URI, "test_params", tracerEnvironment);
  }

  @Override
  public Collection<TestIdentifier> getFlakyTests(TracerEnvironment tracerEnvironment)
      throws IOException {
    return getTestsData(FLAKY_TESTS_URI, "flaky_test_from_libraries_params", tracerEnvironment);
  }

  private Collection<TestIdentifier> getTestsData(
      String endpoint, String dataType, TracerEnvironment tracerEnvironment) throws IOException {
    String uuid = uuidGenerator.get();
    EnvelopeDto<TracerEnvironment> request =
        new EnvelopeDto<>(new DataDto<>(uuid, dataType, tracerEnvironment));
    String json = requestAdapter.toJson(request);
    RequestBody requestBody = RequestBody.create(JSON, json);
    Collection<DataDto<TestIdentifier>> response =
        backendApi.post(
            endpoint,
            requestBody,
            is -> testIdentifiersResponseAdapter.fromJson(Okio.buffer(Okio.source(is))).data);
    return response.stream().map(DataDto::getAttributes).collect(Collectors.toList());
  }

  private static final class EnvelopeDto<T> {
    private final DataDto<T> data;

    private EnvelopeDto(DataDto<T> data) {
      this.data = data;
    }
  }

  private static final class MultiEnvelopeDto<T> {
    private final Collection<DataDto<T>> data;

    private MultiEnvelopeDto(Collection<DataDto<T>> data) {
      this.data = data;
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
}
