package com.datadog.featureflag;

import static com.datadog.featureflag.EventProxy.PRE_SEND_RETRY;
import static com.datadog.featureflag.EventProxy.RECOVERY_INTERVAL_NANOS;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.communication.http.HttpRetryPolicy.Factory.NEVER_RETRY;
import static datadog.trace.api.intake.Intake.EVENT_PLATFORM;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.BackendApiFactory;
import datadog.communication.HttpResponseException;
import datadog.communication.http.HttpRetryPolicy;
import java.io.IOException;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class EventProxyTest {
  private static final byte[] PAYLOAD = "{}".getBytes(UTF_8);
  private static final String ENDPOINT = "evp_proxy/v4/";

  private final BackendApiFactory factory = mock(BackendApiFactory.class);
  private final BackendApi exposures = mock(BackendApi.class);
  private final BackendApi flagEvaluations = mock(BackendApi.class);
  private final AtomicLong now = new AtomicLong();

  @Test
  void sendsRemoteConfigurationEventsToTheFixedV2EndpointWithPreSendRetry() throws IOException {
    clientsFor(V2_EVP_PROXY_ENDPOINT);
    final EventProxy proxy = proxy(false, false);

    assertTrue(proxy.isAvailable());
    assertTrue(proxy.post("exposures", PAYLOAD));
    assertTrue(proxy.post("flagevaluation", PAYLOAD));

    verify(this.factory)
        .createEvpProxyApiForEndpoint(EVENT_PLATFORM, true, PRE_SEND_RETRY, V2_EVP_PROXY_ENDPOINT);
    verify(this.factory)
        .createEvpProxyApiForEndpoint(EVENT_PLATFORM, false, PRE_SEND_RETRY, V2_EVP_PROXY_ENDPOINT);
    verify(this.factory, never()).discoverEvpProxyEndpoint(anyBoolean(), anyIterable());
    verify(this.exposures).post(eq("exposures"), any(), any(), any(), eq(false));
    verify(this.flagEvaluations).post(eq("flagevaluation"), any(), any(), any(), eq(false));
  }

  @Test
  void sendsAgentlessEventsOnceThroughTheDiscoveredEndpoint() throws IOException {
    when(this.factory.discoverEvpProxyEndpoint(false, emptyList())).thenReturn(ENDPOINT);
    clientsFor(ENDPOINT);
    final EventProxy proxy = proxy(true, true);

    assertTrue(proxy.isAvailable());
    assertTrue(proxy.post("exposures", PAYLOAD));

    verify(this.factory).createEvpProxyApiForEndpoint(EVENT_PLATFORM, true, NEVER_RETRY, ENDPOINT);
    verify(this.factory).createEvpProxyApiForEndpoint(EVENT_PLATFORM, false, NEVER_RETRY, ENDPOINT);
    verify(this.factory, times(1)).discoverEvpProxyEndpoint(anyBoolean(), anyIterable());
  }

  @Test
  void leavesAgentlessEventsToDirectIntakeWithoutTheAgentProxy() throws IOException {
    final EventProxy proxy = proxy(true, true);

    assertFalse(proxy.isAvailable());
    this.now.addAndGet(RECOVERY_INTERVAL_NANOS);
    assertFalse(proxy.post("exposures", PAYLOAD));

    verify(this.factory, times(1)).discoverEvpProxyEndpoint(anyBoolean(), anyIterable());
  }

  @Test
  void recoversTheAgentProxyWhenItIsTheOnlyRoute() throws IOException {
    final EventProxy proxy = proxy(true, false);

    assertTrue(proxy.isAvailable(), "the proxy route stays in place to recover");
    assertFalse(proxy.post("exposures", PAYLOAD));
    verify(this.factory, never()).discoverEvpProxyEndpoint(eq(true), anyIterable());

    this.now.addAndGet(RECOVERY_INTERVAL_NANOS);
    when(this.factory.discoverEvpProxyEndpoint(true, emptyList())).thenReturn(ENDPOINT);
    clientsFor(ENDPOINT);

    assertTrue(proxy.post("exposures", PAYLOAD));
    verify(this.exposures).post(eq("exposures"), any(), any(), any(), eq(false));
  }

  @Test
  void waitsForANewEndpointAfterADefinitiveRejectionWhenItIsTheOnlyRoute() throws IOException {
    when(this.factory.discoverEvpProxyEndpoint(false, emptyList())).thenReturn(ENDPOINT);
    clientsFor(ENDPOINT);
    when(this.exposures.post(any(), any(), any(), any(), anyBoolean()))
        .thenThrow(new ConnectException("refused"));
    final EventProxy proxy = proxy(true, false);

    assertFalse(proxy.post("exposures", PAYLOAD));
    assertFalse(proxy.post("exposures", PAYLOAD));

    verify(this.exposures, times(1)).post(any(), any(), any(), any(), anyBoolean());
  }

  @TableTest({
    "Scenario     | Status | Definitive",
    "forbidden    | 403    | false     ",
    "not found    | 404    | true      ",
    "not allowed  | 405    | true      ",
    "server error | 500    | false     ",
    "rate limited | 429    | false     "
  })
  void classifiesProxyRejections(final int status, final boolean definitive) throws IOException {
    clientsFor(V2_EVP_PROXY_ENDPOINT);
    when(this.exposures.post(any(), any(), any(), any(), anyBoolean()))
        .thenThrow(new HttpResponseException(status, "rejected"));
    final EventProxy proxy = proxy(false, false);

    if (definitive) {
      assertFalse(proxy.post("exposures", PAYLOAD));
    } else {
      assertThrows(HttpResponseException.class, () -> proxy.post("exposures", PAYLOAD));
    }
  }

  @Test
  void reportsConnectionRefusalAsDefinitive() throws IOException {
    clientsFor(V2_EVP_PROXY_ENDPOINT);
    when(this.exposures.post(any(), any(), any(), any(), anyBoolean()))
        .thenThrow(new ConnectException("refused"));

    assertFalse(proxy(false, false).post("exposures", PAYLOAD));
  }

  @Test
  void preSendRetryRetriesConnectionRefusalOnly() {
    final HttpRetryPolicy policy = PRE_SEND_RETRY.create();

    assertFalse(policy.shouldRetry(new IOException("reset")));
    assertFalse(policy.shouldRetry((Response) null));
    assertTrue(policy.shouldRetry(new ConnectException("refused")));
    assertTrue(policy.shouldRetry(new ConnectException("refused")));
    assertTrue(policy.shouldRetry(new ConnectException("refused")));
    assertFalse(policy.shouldRetry(new ConnectException("refused")), "at most three retries");
  }

  private EventProxy proxy(final boolean agentless, final boolean directFallbackAvailable) {
    return new EventProxy(this.factory, agentless, directFallbackAvailable, this.now::get);
  }

  private void clientsFor(final String endpoint) {
    when(this.factory.createEvpProxyApiForEndpoint(
            eq(EVENT_PLATFORM), eq(true), any(), eq(endpoint)))
        .thenReturn(this.exposures);
    when(this.factory.createEvpProxyApiForEndpoint(
            eq(EVENT_PLATFORM), eq(false), any(), eq(endpoint)))
        .thenReturn(this.flagEvaluations);
  }
}
