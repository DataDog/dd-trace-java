package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.ddagent.TracerVersion;
import datadog.trace.api.intake.Intake;
import java.util.HashMap;
import java.util.Map;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

class FeatureFlagEvpPublisherTest {

  @Test
  void defaultPublisherRequestsResponseCompression() {
    final BackendApi backendApi = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(Intake.EVENT_PLATFORM, true)).thenReturn(backendApi);

    final FeatureFlagEvpPublisher<TestRequest> publisher =
        new FeatureFlagEvpPublisher<>(factory, TestRequest.class);

    publisher.start();

    verify(factory).createBackendApi(Intake.EVENT_PLATFORM, true);
    verifyNoMoreInteractions(factory);
  }

  @Test
  void responseCompressionCanBeDisabled() throws Exception {
    final BackendApi backendApi = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(Intake.EVENT_PLATFORM, false)).thenReturn(backendApi);

    final FeatureFlagEvpPublisher<TestRequest> publisher =
        new FeatureFlagEvpPublisher<>(factory, TestRequest.class, false);

    publisher.post("flagevaluation", new TestRequest("value"));

    verify(factory).createBackendApi(Intake.EVENT_PLATFORM, false);
    verify(backendApi)
        .post(
            eq("flagevaluation"),
            any(RequestBody.class),
            any(),
            isNull(),
            eq(false),
            eq(flagEvaluationHeaders()));
  }

  @Test
  void exposureRequestsDoNotIncludeFlagEvaluationHeaders() throws Exception {
    final BackendApi backendApi = mock(BackendApi.class);
    final FeatureFlagEvpPublisher<TestRequest> publisher =
        new FeatureFlagEvpPublisher<>(() -> backendApi, TestRequest.class);

    publisher.post("exposures", new TestRequest("value"));

    verify(backendApi)
        .post(eq("exposures"), any(RequestBody.class), any(), isNull(), eq(false), eq(emptyMap()));
  }

  @Test
  void postThrowsWhenEvpBackendApiCannotBeCreated() {
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    final FeatureFlagEvpPublisher<TestRequest> publisher =
        new FeatureFlagEvpPublisher<>(factory, TestRequest.class);

    assertFalse(publisher.start());
    assertThrows(
        IllegalStateException.class,
        () -> publisher.post("flagevaluation", FeatureFlagEvpPublisher.utf8Bytes("{}")));
  }

  private static Map<String, String> flagEvaluationHeaders() {
    final Map<String, String> headers = new HashMap<>();
    headers.put("DD-EVP-ORIGIN", "dd-trace-java");
    headers.put("DD-EVP-ORIGIN-VERSION", TracerVersion.TRACER_VERSION);
    return headers;
  }

  static class TestRequest {
    public final String value;

    TestRequest(final String value) {
      this.value = value;
    }
  }
}
