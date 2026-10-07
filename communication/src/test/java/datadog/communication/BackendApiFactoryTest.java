package datadog.communication;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.api.intake.Intake;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BackendApiFactoryTest {

  private static final MediaType JSON = MediaType.parse("application/json");

  @ParameterizedTest
  @ValueSource(strings = {"datadoghq.com", "custom.example", "DATADOGHQ.EU"})
  void eventPlatformDirectIntakeUsesExactHttpsHost(String site) {
    final HttpUrl url = BackendApiFactory.buildEventPlatformIntakeUrl(site);

    assertEquals("https", url.scheme());
    assertEquals("event-platform-intake." + site.toLowerCase(Locale.ROOT), url.host());
    assertEquals(443, url.port());
    assertEquals("/api/v2/", url.encodedPath());
    assertEquals("", url.username());
    assertEquals("", url.password());
    assertNull(url.encodedQuery());
    assertNull(url.encodedFragment());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "datadoghq.com@evil.example",
        "datadoghq.com:password@evil.example",
        "https://datadoghq.com",
        "datadoghq.com:443",
        "datadoghq.com:8443",
        "datadoghq.com/path",
        "datadoghq.com?query=value",
        "datadoghq.com#fragment",
        "dätadoghq.com",
        "data doghq.com",
        " datadoghq.com",
        "datadoghq.com ",
        "datadoghq.com\\evil.example"
      })
  void eventPlatformDirectIntakeRejectsUnsafeSite(String site) {
    assertThrows(
        IllegalArgumentException.class, () -> BackendApiFactory.buildEventPlatformIntakeUrl(site));
  }

  @ParameterizedTest
  @ValueSource(ints = {301, 302, 307, 308})
  void featureFlagDirectIntakeDoesNotFollowRedirects(final int statusCode) throws Exception {
    final MockWebServer intake = new MockWebServer();
    final MockWebServer redirectTarget = new MockWebServer();
    final OkHttpClient sharedClient = new OkHttpClient.Builder().build();
    final OkHttpClient directClient = BackendApiFactory.directIntakeHttpClient(sharedClient, false);
    redirectTarget.start();
    intake.enqueue(
        new MockResponse()
            .setResponseCode(statusCode)
            .setHeader("Location", redirectTarget.url("/redirected")));
    intake.start();
    try {
      final IntakeApi api =
          new IntakeApi(
              intake.url("/api/v2/"),
              "api-key",
              "123",
              HttpRetryPolicy.Factory.NEVER_RETRY,
              directClient,
              false);

      assertThrows(
          IOException.class,
          () ->
              api.post(
                  "flagevaluation",
                  RequestBody.create(JSON, "{}".getBytes(StandardCharsets.UTF_8)),
                  stream -> null,
                  null,
                  false));

      final RecordedRequest request = intake.takeRequest();
      assertEquals("api-key", request.getHeader("DD-API-KEY"));
      assertEquals(1, intake.getRequestCount());
      assertEquals(0, redirectTarget.getRequestCount());
    } finally {
      directClient.dispatcher().executorService().shutdownNow();
      directClient.connectionPool().evictAll();
      sharedClient.dispatcher().executorService().shutdownNow();
      sharedClient.connectionPool().evictAll();
      intake.shutdown();
      redirectTarget.shutdown();
    }
  }

  @Test
  void noBackendApiWhenAgentDoesNotAdvertiseEvpProxy() {
    final FakeFeaturesDiscovery discovery = new FakeFeaturesDiscovery(null);
    final BackendApiFactory factory =
        new BackendApiFactory(Config.get(), sharedCommunicationObjects(discovery, null));

    assertNull(factory.createBackendApi(Intake.EVENT_PLATFORM, false));
  }

  @Test
  void advertisedEvpProxyEndpointSupportsDisabledResponseCompression() throws Exception {
    final MockWebServer agent = new MockWebServer();
    agent.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    agent.start();
    try {
      final FakeFeaturesDiscovery discovery = new FakeFeaturesDiscovery(V4_EVP_PROXY_ENDPOINT);
      final BackendApiFactory factory =
          new BackendApiFactory(
              Config.get(), sharedCommunicationObjects(discovery, agent.url("/")));
      final BackendApi api = factory.createBackendApi(Intake.EVENT_PLATFORM, false);

      assertNotNull(api);
      api.post(
          "flagevaluation",
          RequestBody.create(JSON, "{}".getBytes(StandardCharsets.UTF_8)),
          stream -> null,
          null,
          false);

      final RecordedRequest request = agent.takeRequest();
      assertEquals("/evp_proxy/v4/api/v2/flagevaluation", request.getPath());
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void explicitNoRetryProxyPolicyDoesNotReplayAmbiguousFailure() throws Exception {
    final MockWebServer agent = new MockWebServer();
    agent.enqueue(new MockResponse().setResponseCode(500).setBody("ambiguous"));
    agent.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    agent.start();
    try {
      final FakeFeaturesDiscovery discovery = new FakeFeaturesDiscovery(V4_EVP_PROXY_ENDPOINT);
      final BackendApiFactory factory =
          new BackendApiFactory(
              Config.get(), sharedCommunicationObjects(discovery, agent.url("/")));
      final BackendApi api =
          factory.createEvpProxyApi(
              Intake.EVENT_PLATFORM, false, HttpRetryPolicy.Factory.NEVER_RETRY);

      assertNotNull(api);
      assertThrows(
          HttpResponseException.class,
          () ->
              api.post(
                  "flagevaluation",
                  RequestBody.create(JSON, "{}".getBytes(StandardCharsets.UTF_8)),
                  stream -> null,
                  null,
                  false));

      assertEquals(1, agent.getRequestCount());
    } finally {
      agent.shutdown();
    }
  }

  private static SharedCommunicationObjects sharedCommunicationObjects(
      final DDAgentFeaturesDiscovery discovery, final HttpUrl agentUrl) {
    final TestSharedCommunicationObjects sco = new TestSharedCommunicationObjects(discovery);
    sco.agentUrl = agentUrl != null ? agentUrl : HttpUrl.get("http://localhost:8126/");
    sco.agentHttpClient = new OkHttpClient();
    return sco;
  }

  private static final class TestSharedCommunicationObjects extends SharedCommunicationObjects {
    private final DDAgentFeaturesDiscovery discovery;

    private TestSharedCommunicationObjects(final DDAgentFeaturesDiscovery discovery) {
      this.discovery = discovery;
    }

    @Override
    public DDAgentFeaturesDiscovery featuresDiscovery(final Config config) {
      return discovery;
    }
  }

  private static final class FakeFeaturesDiscovery extends DDAgentFeaturesDiscovery {
    private final String evpProxyEndpoint;

    private FakeFeaturesDiscovery(final String evpProxyEndpoint) {
      super(
          new OkHttpClient(),
          Monitoring.DISABLED,
          HttpUrl.get("http://localhost:8126/"),
          ProtocolVersion.V0_5,
          true,
          false);
      this.evpProxyEndpoint = evpProxyEndpoint;
    }

    @Override
    public void discoverIfOutdated() {}

    @Override
    public String getEvpProxyEndpoint() {
      return evpProxyEndpoint;
    }

    @Override
    public boolean supportsEvpProxy() {
      return evpProxyEndpoint != null;
    }
  }
}
