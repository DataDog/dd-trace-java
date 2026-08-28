package datadog.communication;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static java.util.Collections.singletonMap;
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
import java.nio.charset.StandardCharsets;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class BackendApiFactoryTest {

  private static final MediaType JSON = MediaType.parse("application/json");

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
              Config.get(),
              sharedCommunicationObjects(discovery, agent.url("/")),
              singletonMap("DD-EVP-ORIGIN", "dd-trace-java"));
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
      assertEquals("dd-trace-java", request.getHeader("DD-EVP-ORIGIN"));
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
