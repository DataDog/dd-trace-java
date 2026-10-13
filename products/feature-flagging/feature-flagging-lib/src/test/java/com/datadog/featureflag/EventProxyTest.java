package com.datadog.featureflag;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.HttpResponseException;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.intake.Intake;
import java.io.IOException;
import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class EventProxyTest {
  private static final byte[] PAYLOAD = "{}".getBytes(UTF_8);

  @Test
  void isUnavailableWithoutAgentProxySupport() throws IOException {
    final EventProxy proxy = new EventProxy(mock(BackendApiFactory.class), false);

    assertFalse(proxy.isAvailable());
    assertFalse(proxy.post("exposures", PAYLOAD));
  }

  @Test
  void postsEachRouteThroughItsOwnClient() throws IOException {
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    final BackendApi exposures = mock(BackendApi.class);
    final BackendApi flagEvaluations = mock(BackendApi.class);
    when(factory.createEvpProxyApi(Intake.EVENT_PLATFORM, true)).thenReturn(exposures);
    when(factory.createEvpProxyApi(Intake.EVENT_PLATFORM, false)).thenReturn(flagEvaluations);
    final EventProxy proxy = new EventProxy(factory, false);

    assertTrue(proxy.isAvailable());
    assertTrue(proxy.post("exposures", PAYLOAD));
    assertTrue(proxy.post("flagevaluation", PAYLOAD));

    verify(exposures).post(eq("exposures"), any(), any(), any(), eq(false));
    verify(flagEvaluations).post(eq("flagevaluation"), any(), any(), any(), eq(false));
  }

  @Test
  void doesNotRetryWhenDirectFallbackIsAvailable() {
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    final EventProxy proxy = new EventProxy(factory, true);

    proxy.isAvailable();

    verify(factory)
        .createEvpProxyApi(Intake.EVENT_PLATFORM, true, HttpRetryPolicy.Factory.NEVER_RETRY);
    verify(factory)
        .createEvpProxyApi(Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY);
  }

  @Test
  void reportsConnectionFailuresAsUnavailable() throws IOException {
    final BackendApi api = proxyApi(new ConnectException("refused"));

    assertFalse(new EventProxy(factoryOf(api), false).post("exposures", PAYLOAD));
  }

  @TableTest({
    "Scenario     | Status | Definitive",
    "forbidden    | 403    | true      ",
    "not found    | 404    | true      ",
    "not allowed  | 405    | true      ",
    "server error | 500    | false     ",
    "rate limited | 429    | false     "
  })
  void classifiesProxyRejections(final int status, final boolean definitive) throws IOException {
    final BackendApi api = proxyApi(new HttpResponseException(status, "rejected"));
    final EventProxy proxy = new EventProxy(factoryOf(api), false);

    if (definitive) {
      assertFalse(proxy.post("exposures", PAYLOAD));
    } else {
      assertThrows(HttpResponseException.class, () -> proxy.post("exposures", PAYLOAD));
    }
  }

  private static BackendApi proxyApi(final IOException failure) throws IOException {
    final BackendApi api = mock(BackendApi.class);
    when(api.post(any(), any(), any(), any(), anyBoolean())).thenThrow(failure);
    return api;
  }

  private static BackendApiFactory factoryOf(final BackendApi api) {
    final BackendApiFactory factory = mock(BackendApiFactory.class);
    when(factory.createEvpProxyApi(eq(Intake.EVENT_PLATFORM), anyBoolean())).thenReturn(api);
    return factory;
  }
}
