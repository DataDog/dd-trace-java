package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.communication.BackendApi;
import datadog.communication.HttpResponseException;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.util.IOThrowingFunction;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentlessFeatureFlagBackendApiTest {

  @ParameterizedTest
  @ValueSource(ints = {403, 404, 405})
  void replaysRejectedBatchDirectlyAndKeepsDirectRoute(final int statusCode) throws Exception {
    final RecordingBackendApi local =
        new RecordingBackendApi(new HttpResponseException(statusCode, "rejected"));
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            () -> {
              directApiCreations.incrementAndGet();
              return direct;
            },
            "flag evaluation");
    final RequestBody firstBody = requestBody("first");
    final RequestBody secondBody = requestBody("second");

    assertEquals(0, directApiCreations.get());
    api.post("flagevaluation", firstBody, stream -> null, null, false);
    api.post("flagevaluation", secondBody, stream -> null, null, false);

    assertEquals(1, directApiCreations.get());
    assertEquals(1, local.calls);
    assertEquals(2, direct.calls);
    assertSame(firstBody, local.requestBodies.get(0));
    assertSame(firstBody, direct.requestBodies.get(0));
    assertSame(secondBody, direct.requestBodies.get(1));
  }

  @ParameterizedTest
  @MethodSource("featureFlagRoutes")
  void fallsBackAfterConnectionRefusal(final String route, final String eventType)
      throws Exception {
    final RecordingBackendApi local =
        new RecordingBackendApi(new ConnectException("connection refused"));
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(local, () -> direct, eventType);

    api.post(route, requestBody(eventType), stream -> null, null, false);

    assertEquals(1, local.calls);
    assertEquals(1, direct.calls);
  }

  @Test
  void doesNotReturnToLocalRouteAfterSwitchingToDirectIntake() throws Exception {
    final RecordingBackendApi local =
        new RecordingBackendApi(new ConnectException("connection refused"));
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(local, () -> direct, "exposure");

    api.post("exposures", requestBody("first"), stream -> null, null, false);
    direct.failure = new IOException("direct intake failed");

    assertThrows(
        IOException.class,
        () -> api.post("exposures", requestBody("second"), stream -> null, null, false));
    assertEquals(1, local.calls);
    assertEquals(2, direct.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500})
  void staysOnLocalRouteAfterNonFallbackHttpFailure(final int statusCode) {
    assertNoDirectSwitch(new HttpResponseException(statusCode, "non-fallback"));
  }

  @Test
  void switchesFutureBatchToDirectIntakeAfterTimeout() throws Exception {
    assertFutureBatchUsesDirectIntake(new SocketTimeoutException("timed out"));
  }

  @Test
  void switchesFutureBatchToDirectIntakeAfterConnectionReset() throws Exception {
    assertFutureBatchUsesDirectIntake(new SocketException("connection reset"));
  }

  @Test
  void switchesFutureBatchToDirectIntakeAfterBrokenPipe() throws Exception {
    assertFutureBatchUsesDirectIntake(new SocketException("broken pipe"));
  }

  @Test
  void doesNotRetryDirectApiCreationWhenFallbackIsUnavailable() {
    final RecordingBackendApi local =
        new RecordingBackendApi(new HttpResponseException(404, "rejected"));
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            () -> {
              directApiCreations.incrementAndGet();
              return null;
            },
            "exposure");

    assertThrows(
        HttpResponseException.class,
        () -> api.post("exposures", requestBody("first"), stream -> null, null, false));
    assertThrows(
        HttpResponseException.class,
        () -> api.post("exposures", requestBody("second"), stream -> null, null, false));

    assertEquals(2, local.calls);
    assertEquals(1, directApiCreations.get());
  }

  private static void assertNoDirectSwitch(final IOException failure) {
    final RecordingBackendApi local = new RecordingBackendApi(failure);
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            () -> {
              directApiCreations.incrementAndGet();
              return direct;
            },
            "flag evaluation");

    assertThrows(
        IOException.class,
        () -> api.post("flagevaluation", requestBody("evaluation"), stream -> null, null, false));
    assertThrows(
        IOException.class,
        () -> api.post("flagevaluation", requestBody("next"), stream -> null, null, false));

    assertEquals(2, local.calls);
    assertEquals(0, direct.calls);
    assertEquals(0, directApiCreations.get());
  }

  private static void assertFutureBatchUsesDirectIntake(final IOException failure)
      throws Exception {
    final RecordingBackendApi local = new RecordingBackendApi(failure);
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            () -> {
              directApiCreations.incrementAndGet();
              return direct;
            },
            "flag evaluation");
    final RequestBody ambiguousBody = requestBody("ambiguous");
    final RequestBody nextBody = requestBody("next");

    assertThrows(
        IOException.class,
        () -> api.post("flagevaluation", ambiguousBody, stream -> null, null, false));
    api.post("flagevaluation", nextBody, stream -> null, null, false);

    assertEquals(1, local.calls);
    assertEquals(1, direct.calls);
    assertEquals(1, directApiCreations.get());
    assertSame(ambiguousBody, local.requestBodies.get(0));
    assertSame(nextBody, direct.requestBodies.get(0));
  }

  private static RequestBody requestBody(final String value) {
    return RequestBody.create(MediaType.parse("application/json"), value);
  }

  private static Stream<Arguments> featureFlagRoutes() {
    return Stream.of(
        Arguments.of("exposures", "exposure"), Arguments.of("flagevaluation", "flag evaluation"));
  }

  private static final class RecordingBackendApi implements BackendApi {
    private IOException failure;
    private final List<RequestBody> requestBodies = new ArrayList<>();
    private int calls;

    private RecordingBackendApi() {
      this(null);
    }

    private RecordingBackendApi(@Nullable final IOException failure) {
      this.failure = failure;
    }

    @Override
    public <T> T post(
        final String uri,
        final RequestBody requestBody,
        final IOThrowingFunction<InputStream, T> responseParser,
        @Nullable final OkHttpUtils.CustomListener requestListener,
        final boolean requestCompression)
        throws IOException {
      calls++;
      requestBodies.add(requestBody);
      if (failure != null) {
        throw failure;
      }
      return null;
    }
  }
}
