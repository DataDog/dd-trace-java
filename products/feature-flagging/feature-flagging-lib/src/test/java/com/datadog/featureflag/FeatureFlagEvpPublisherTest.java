package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.ExposuresRequest;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import datadog.trace.api.intake.Intake;
import java.nio.charset.StandardCharsets;
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

  @Test
  void serializesSerialIdUnderTheIntakeWireKey() {
    assertTrue(exposureJson(340132).contains("\"serial_id\":340132"));
  }

  @Test
  void serializesSerialIdZeroRatherThanOmittingIt() {
    assertTrue(exposureJson(0).contains("\"serial_id\":0"));
  }

  @Test
  void omitsSerialIdKeyWhenAbsent() {
    assertFalse(exposureJson(null).contains("serial_id"));
  }

  @Test
  void omitsSerialIdKeyForAnEventBuiltWithoutOne() {
    final ExposureEvent event =
        new ExposureEvent(
            1234L,
            new Allocation("allocation"),
            new Flag("flag"),
            new Variant("variant"),
            new Subject("subject", emptyMap()));

    assertFalse(exposureJsonOf(event).contains("serial_id"));
  }

  private static String exposureJson(final Integer serialId) {
    return exposureJsonOf(
        new ExposureEvent(
            1234L,
            new Allocation("allocation"),
            new Flag("flag"),
            new Variant("variant"),
            new Subject("subject", emptyMap()),
            serialId));
  }

  private static String exposureJsonOf(final ExposureEvent event) {
    final FeatureFlagEvpPublisher<ExposuresRequest> publisher =
        new FeatureFlagEvpPublisher<>(mock(BackendApiFactory.class), ExposuresRequest.class);
    return new String(
        publisher.serialize(new ExposuresRequest(emptyMap(), singletonList(event))),
        StandardCharsets.UTF_8);
  }

  static class TestRequest {
    public final String value;

    TestRequest(final String value) {
      this.value = value;
    }
  }
}
