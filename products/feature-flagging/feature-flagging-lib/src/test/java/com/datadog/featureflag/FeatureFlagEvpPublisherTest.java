package com.datadog.featureflag;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
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
import datadog.trace.api.intake.Intake;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

class FeatureFlagEvpPublisherTest {

  @Test
  void defaultPublisherUsesDefaultBackendApiFactoryPath() {
    final BackendApi backendApi = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(Intake.EVENT_PLATFORM)).thenReturn(backendApi);

    final FeatureFlagEvpPublisher<TestRequest> publisher =
        new FeatureFlagEvpPublisher<>(factory, TestRequest.class);

    publisher.start();

    verify(factory).createBackendApi(Intake.EVENT_PLATFORM);
    verifyNoMoreInteractions(factory);
  }

  @Test
  void responseCompressionCanBeDisabledWithoutPinnedEndpoint() {
    final BackendApi backendApi = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(Intake.EVENT_PLATFORM, null, false)).thenReturn(backendApi);

    final FeatureFlagEvpPublisher<TestRequest> publisher =
        new FeatureFlagEvpPublisher<>(factory, TestRequest.class, false);

    publisher.start();

    verify(factory).createBackendApi(Intake.EVENT_PLATFORM, null, false);
  }

  @Test
  void preferredEndpointAndRequestCompressionAreForwardedToBackendApi() throws Exception {
    final BackendApi backendApi = mock(BackendApi.class);
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createBackendApi(Intake.EVENT_PLATFORM, V4_EVP_PROXY_ENDPOINT, false))
        .thenReturn(backendApi);
    final FeatureFlagEvpPublisher<TestRequest> publisher =
        new FeatureFlagEvpPublisher<>(factory, TestRequest.class, V4_EVP_PROXY_ENDPOINT, false);

    publisher.post("flagevaluation", new TestRequest("value"));

    verify(factory).createBackendApi(Intake.EVENT_PLATFORM, V4_EVP_PROXY_ENDPOINT, false);
    verify(backendApi)
        .post(eq("flagevaluation"), any(RequestBody.class), any(), isNull(), eq(false));
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

  static class TestRequest {
    public final String value;

    TestRequest(final String value) {
      this.value = value;
    }
  }
}
