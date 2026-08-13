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
import javax.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentlessFeatureFlagBackendApiTest {

  @ParameterizedTest
  @ValueSource(ints = {403, 404, 405})
  void replaysRejectedBatchDirectlyAndKeepsDirectRoute(final int statusCode) throws Exception {
    final RecordingBackendApi local =
        new RecordingBackendApi(new HttpResponseException(statusCode, "rejected"));
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(local, direct, "flag evaluation");
    final RequestBody firstBody = requestBody("first");
    final RequestBody secondBody = requestBody("second");

    api.post("flagevaluation", firstBody, stream -> null, null, false);
    api.post("flagevaluation", secondBody, stream -> null, null, false);

    assertEquals(1, local.calls);
    assertEquals(2, direct.calls);
    assertSame(firstBody, local.requestBodies.get(0));
    assertSame(firstBody, direct.requestBodies.get(0));
    assertSame(secondBody, direct.requestBodies.get(1));
  }

  @Test
  void fallsBackAfterConnectionRefusal() throws Exception {
    final RecordingBackendApi local =
        new RecordingBackendApi(new ConnectException("connection refused"));
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(local, direct, "flag evaluation");

    api.post("flagevaluation", requestBody("evaluation"), stream -> null, null, false);

    assertEquals(1, local.calls);
    assertEquals(1, direct.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500})
  void doesNotReplayAmbiguousHttpFailure(final int statusCode) {
    assertNoDirectReplay(new HttpResponseException(statusCode, "ambiguous"));
  }

  @Test
  void doesNotReplayTimeout() {
    assertNoDirectReplay(new SocketTimeoutException("timed out"));
  }

  @Test
  void doesNotReplayConnectionReset() {
    assertNoDirectReplay(new SocketException("connection reset"));
  }

  private static void assertNoDirectReplay(final IOException failure) {
    final RecordingBackendApi local = new RecordingBackendApi(failure);
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(local, direct, "flag evaluation");

    assertThrows(
        IOException.class,
        () -> api.post("flagevaluation", requestBody("evaluation"), stream -> null, null, false));

    assertEquals(1, local.calls);
    assertEquals(0, direct.calls);
  }

  private static RequestBody requestBody(final String value) {
    return RequestBody.create(MediaType.parse("application/json"), value);
  }

  private static final class RecordingBackendApi implements BackendApi {
    private final IOException failure;
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
