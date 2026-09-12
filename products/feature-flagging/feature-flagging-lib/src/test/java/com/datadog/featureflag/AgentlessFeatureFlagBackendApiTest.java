package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
  @ValueSource(ints = {404, 405})
  void replaysRejectedBatchDirectlyAndKeepsDirectRoute(final int statusCode) throws Exception {
    final RecordingBackendApi local =
        new RecordingBackendApi(new HttpResponseException(statusCode, "rejected"));
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            null,
            () -> local,
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
        new AgentlessFeatureFlagBackendApi(local, null, () -> local, () -> direct, eventType);

    api.post(route, requestBody(eventType), stream -> null, null, false);

    assertEquals(1, local.calls);
    assertEquals(1, direct.calls);
  }

  @Test
  void keepsDirectRouteStickyAfterLocalFailure() throws Exception {
    final AtomicLong clock = new AtomicLong();
    final RecordingBackendApi unavailableLocal =
        new RecordingBackendApi(new ConnectException("connection refused"));
    final RecordingBackendApi recoveredLocal = new RecordingBackendApi();
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            unavailableLocal, null, () -> recoveredLocal, () -> direct, "exposure", clock::get, 10);

    api.post("exposures", requestBody("first"), stream -> null, null, false);
    clock.set(9);
    api.post("exposures", requestBody("second"), stream -> null, null, false);
    clock.set(10);
    api.post("exposures", requestBody("third"), stream -> null, null, false);

    assertEquals(1, unavailableLocal.calls);
    assertEquals(3, direct.calls);
    assertEquals(0, recoveredLocal.calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 429, 500})
  void doesNotReplayAmbiguousHttpFailureButSwitchesFutureBatches(final int statusCode)
      throws Exception {
    assertNoSameBatchReplayButUsesDirectForNext(new HttpResponseException(statusCode, "ambiguous"));
  }

  @Test
  void doesNotReplayTimeoutButSwitchesFutureBatches() throws Exception {
    assertNoSameBatchReplayButUsesDirectForNext(new SocketTimeoutException("timed out"));
  }

  @Test
  void doesNotReplayConnectionResetButSwitchesFutureBatches() throws Exception {
    assertNoSameBatchReplayButUsesDirectForNext(new SocketException("connection reset"));
  }

  @Test
  void startsDirectAndNeverProbesLocal() throws Exception {
    final AtomicLong clock = new AtomicLong();
    final RecordingBackendApi direct = new RecordingBackendApi();
    final RecordingBackendApi recoveredLocal = new RecordingBackendApi();
    final AtomicInteger proxyApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            null,
            direct,
            () -> {
              proxyApiCreations.incrementAndGet();
              return recoveredLocal;
            },
            () -> direct,
            "exposure",
            clock::get,
            10);

    api.post("exposures", requestBody("first"), stream -> null, null, false);
    clock.set(9);
    api.post("exposures", requestBody("second"), stream -> null, null, false);
    clock.set(10);
    api.post("exposures", requestBody("third"), stream -> null, null, false);

    assertEquals(0, proxyApiCreations.get());
    assertEquals(3, direct.calls);
    assertEquals(0, recoveredLocal.calls);
  }

  @Test
  void concurrentSendersDoNotBlockOnOrDuplicateARecoveryProbe() throws Exception {
    final AtomicLong clock = new AtomicLong(10);
    final RecordingBackendApi recoveredLocal = new RecordingBackendApi();
    final AtomicInteger proxyApiCreations = new AtomicInteger();
    final CountDownLatch probeStarted = new CountDownLatch(1);
    final CountDownLatch releaseProbe = new CountDownLatch(1);
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            null,
            null,
            () -> {
              proxyApiCreations.incrementAndGet();
              probeStarted.countDown();
              try {
                assertTrue(releaseProbe.await(5, TimeUnit.SECONDS));
              } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
              }
              return recoveredLocal;
            },
            () -> null,
            "flag evaluation",
            clock::get,
            10);

    // Construction schedules the first probe for t=20.
    clock.set(20);
    final CompletableFuture<Void> recoveringPost =
        CompletableFuture.runAsync(
            () -> {
              try {
                api.post("flagevaluation", requestBody("probe"), stream -> null, null, false);
              } catch (final IOException exception) {
                throw new AssertionError(exception);
              }
            });
    assertTrue(probeStarted.await(5, TimeUnit.SECONDS));

    assertThrows(
        IOException.class,
        () -> api.post("flagevaluation", requestBody("parallel"), stream -> null, null, false));
    releaseProbe.countDown();
    recoveringPost.get(5, TimeUnit.SECONDS);

    assertEquals(1, proxyApiCreations.get());
    assertEquals(1, recoveredLocal.calls);
  }

  @Test
  void failedRecoveryIsStickyForAnotherCooldown() throws Exception {
    final AtomicLong clock = new AtomicLong();
    final AtomicInteger proxyApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            null,
            null,
            () -> {
              proxyApiCreations.incrementAndGet();
              return null;
            },
            () -> null,
            "exposure",
            clock::get,
            10);

    clock.set(10);
    assertThrows(
        IOException.class,
        () -> api.post("exposures", requestBody("first"), stream -> null, null, false));
    assertThrows(
        IOException.class,
        () -> api.post("exposures", requestBody("second"), stream -> null, null, false));
    clock.set(20);
    assertThrows(
        IOException.class,
        () -> api.post("exposures", requestBody("third"), stream -> null, null, false));

    assertEquals(2, proxyApiCreations.get());
  }

  @Test
  void doesNotRetryDirectApiCreationWhenFallbackIsUnavailable() {
    final RecordingBackendApi local =
        new RecordingBackendApi(new HttpResponseException(404, "rejected"));
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            null,
            () -> local,
            () -> {
              directApiCreations.incrementAndGet();
              return null;
            },
            "exposure");

    assertThrows(
        HttpResponseException.class,
        () -> api.post("exposures", requestBody("first"), stream -> null, null, false));
    assertThrows(
        IOException.class,
        () -> api.post("exposures", requestBody("second"), stream -> null, null, false));

    assertEquals(1, local.calls);
    assertEquals(1, directApiCreations.get());
  }

  @Test
  void permitsUnavailableStartupSoLocalDeliveryCanRecover() {
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(null, null, () -> null, () -> null, "flag evaluation");

    assertThrows(
        IOException.class,
        () -> api.post("flagevaluation", requestBody("first"), stream -> null, null, false));
  }

  @Test
  void propagatesDirectFailuresWithoutTryingToFallback() {
    final SocketTimeoutException failure = new SocketTimeoutException("direct timeout");
    final RecordingBackendApi direct = new RecordingBackendApi(failure);
    final AtomicInteger proxyApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            null,
            direct,
            () -> {
              proxyApiCreations.incrementAndGet();
              return null;
            },
            () -> direct,
            "exposure");

    assertSame(
        failure,
        assertThrows(
            SocketTimeoutException.class,
            () -> api.post("exposures", requestBody("direct"), stream -> null, null, false)));
    assertEquals(0, proxyApiCreations.get());
    assertEquals(1, direct.calls);
  }

  @Test
  void concurrentProxyFailuresOnlyTransitionTheMatchingRouteOnce() throws Exception {
    final CountDownLatch localCallsStarted = new CountDownLatch(2);
    final CountDownLatch releaseFailures = new CountDownLatch(1);
    final BackendApi local =
        new BackendApi() {
          @Override
          public <T> T post(
              final String uri,
              final RequestBody requestBody,
              final IOThrowingFunction<InputStream, T> responseParser,
              @Nullable final OkHttpUtils.CustomListener requestListener,
              final boolean requestCompression)
              throws IOException {
            localCallsStarted.countDown();
            try {
              assertTrue(releaseFailures.await(5, TimeUnit.SECONDS));
            } catch (final InterruptedException exception) {
              Thread.currentThread().interrupt();
              throw new AssertionError(exception);
            }
            throw new HttpResponseException(404, "local route missing");
          }
        };
    final AtomicInteger directCalls = new AtomicInteger();
    final BackendApi direct =
        new BackendApi() {
          @Override
          public <T> T post(
              final String uri,
              final RequestBody requestBody,
              final IOThrowingFunction<InputStream, T> responseParser,
              @Nullable final OkHttpUtils.CustomListener requestListener,
              final boolean requestCompression) {
            directCalls.incrementAndGet();
            return null;
          }
        };
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            null,
            () -> local,
            () -> {
              directApiCreations.incrementAndGet();
              return direct;
            },
            "exposure");

    final CompletableFuture<Void> first =
        CompletableFuture.runAsync(() -> postWithoutFailure(api, "first concurrent proxy request"));
    final CompletableFuture<Void> second =
        CompletableFuture.runAsync(
            () -> postWithoutFailure(api, "second concurrent proxy request"));
    assertTrue(localCallsStarted.await(5, TimeUnit.SECONDS));
    releaseFailures.countDown();
    first.get(5, TimeUnit.SECONDS);
    second.get(5, TimeUnit.SECONDS);

    assertEquals(1, directApiCreations.get());
    assertEquals(2, directCalls.get());
  }

  @Test
  void sharesRouteTransitionsAcrossExposureAndFlagEvaluationWriters() throws Exception {
    final FeatureFlagRouteSelector routeSelector = new FeatureFlagRouteSelector();
    final RecordingBackendApi exposureLocal =
        new RecordingBackendApi(new SocketTimeoutException("ambiguous timeout"));
    final RecordingBackendApi exposureDirect = new RecordingBackendApi();
    final RecordingBackendApi evaluationLocal = new RecordingBackendApi();
    final RecordingBackendApi evaluationDirect = new RecordingBackendApi();
    final AgentlessFeatureFlagBackendApi exposureApi =
        new AgentlessFeatureFlagBackendApi(
            exposureLocal,
            exposureDirect,
            () -> exposureLocal,
            () -> exposureDirect,
            "exposure",
            routeSelector);
    final AgentlessFeatureFlagBackendApi evaluationApi =
        new AgentlessFeatureFlagBackendApi(
            evaluationLocal,
            evaluationDirect,
            () -> evaluationLocal,
            () -> evaluationDirect,
            "flag evaluation",
            routeSelector);

    assertThrows(
        SocketTimeoutException.class,
        () -> exposureApi.post("exposures", requestBody("first"), stream -> null, null, false));
    evaluationApi.post("flagevaluation", requestBody("second"), stream -> null, null, false);

    assertEquals(1, exposureLocal.calls);
    assertEquals(0, exposureDirect.calls);
    assertEquals(0, evaluationLocal.calls);
    assertEquals(1, evaluationDirect.calls);
  }

  @Test
  void workingDirectRouteDoesNotAttemptRecovery() throws Exception {
    final AtomicLong clock = new AtomicLong();
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AtomicInteger proxyApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            null,
            direct,
            () -> {
              proxyApiCreations.incrementAndGet();
              throw new IllegalStateException("discovery failed");
            },
            () -> direct,
            "exposure",
            clock::get,
            10);

    clock.set(10);
    api.post("exposures", requestBody("survives recovery failure"), stream -> null, null, false);

    assertEquals(1, direct.calls);
    assertEquals(0, proxyApiCreations.get());
  }

  private static void assertNoSameBatchReplayButUsesDirectForNext(final IOException failure)
      throws Exception {
    final RecordingBackendApi local = new RecordingBackendApi(failure);
    final RecordingBackendApi direct = new RecordingBackendApi();
    final AtomicInteger directApiCreations = new AtomicInteger();
    final AgentlessFeatureFlagBackendApi api =
        new AgentlessFeatureFlagBackendApi(
            local,
            null,
            () -> local,
            () -> {
              directApiCreations.incrementAndGet();
              return direct;
            },
            "flag evaluation");

    assertThrows(
        IOException.class,
        () -> api.post("flagevaluation", requestBody("evaluation"), stream -> null, null, false));
    api.post("flagevaluation", requestBody("next"), stream -> null, null, false);

    assertEquals(1, local.calls);
    assertEquals(1, direct.calls);
    assertEquals(1, directApiCreations.get());
  }

  private static RequestBody requestBody(final String value) {
    return RequestBody.create(MediaType.parse("application/json"), value);
  }

  private static void postWithoutFailure(
      final AgentlessFeatureFlagBackendApi api, final String body) {
    try {
      api.post("exposures", requestBody(body), stream -> null, null, false);
    } catch (final IOException exception) {
      throw new AssertionError(exception);
    }
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
