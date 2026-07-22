package datadog.communication;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.metrics.api.Monitoring;
import datadog.trace.api.Config;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.api.intake.Intake;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
  void preferredEvpProxyEndpointMustBeAdvertisedByAgent() {
    final FakeFeaturesDiscovery discovery =
        new FakeFeaturesDiscovery(
            V4_EVP_PROXY_ENDPOINT, Collections.singleton(V4_EVP_PROXY_ENDPOINT));
    final BackendApiFactory factory =
        new BackendApiFactory(Config.get(), sharedCommunicationObjects(discovery, null));

    assertNull(factory.createBackendApi(Intake.EVENT_PLATFORM, V2_EVP_PROXY_ENDPOINT, false));
  }

  @Test
  void preferredEvpProxyEndpointUsesRequestedRouteWhenAdvertised() throws Exception {
    final MockWebServer agent = new MockWebServer();
    agent.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    agent.start();
    try {
      final FakeFeaturesDiscovery discovery =
          new FakeFeaturesDiscovery(
              V4_EVP_PROXY_ENDPOINT,
              new HashSet<>(Arrays.asList(V4_EVP_PROXY_ENDPOINT, V2_EVP_PROXY_ENDPOINT)));
      final BackendApiFactory factory =
          new BackendApiFactory(
              Config.get(), sharedCommunicationObjects(discovery, agent.url("/")));
      final BackendApi api =
          factory.createBackendApi(Intake.EVENT_PLATFORM, V2_EVP_PROXY_ENDPOINT, false);

      assertNotNull(api);
      api.post(
          "flagevaluation",
          RequestBody.create(JSON, "{}".getBytes(StandardCharsets.UTF_8)),
          stream -> null,
          null,
          false);

      final RecordedRequest request = agent.takeRequest();
      assertEquals("/evp_proxy/v2/api/v2/flagevaluation", request.getPath());
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void preferredEvpProxyEndpointDoesNotRequireLegacyDefaultRoute() throws Exception {
    final String futureEndpoint = "evp_proxy/v9/";
    final MockWebServer agent = new MockWebServer();
    agent.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    agent.start();
    try {
      final FakeFeaturesDiscovery discovery =
          new FakeFeaturesDiscovery(null, Collections.singleton(futureEndpoint));
      final BackendApiFactory factory =
          new BackendApiFactory(
              Config.get(), sharedCommunicationObjects(discovery, agent.url("/")));
      final BackendApi api = factory.createBackendApi(Intake.EVENT_PLATFORM, futureEndpoint, false);

      assertNotNull(api);
      api.post(
          "flagevaluation",
          RequestBody.create(JSON, "{}".getBytes(StandardCharsets.UTF_8)),
          stream -> null,
          null,
          false);

      final RecordedRequest request = agent.takeRequest();
      assertEquals("/evp_proxy/v9/api/v2/flagevaluation", request.getPath());
    } finally {
      agent.shutdown();
    }
  }

  @Test
  void defaultEvpProxyEndpointSupportsDisabledResponseCompression() throws Exception {
    final MockWebServer agent = new MockWebServer();
    agent.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    agent.start();
    try {
      final FakeFeaturesDiscovery discovery =
          new FakeFeaturesDiscovery(
              V4_EVP_PROXY_ENDPOINT,
              new HashSet<>(Arrays.asList(V4_EVP_PROXY_ENDPOINT, V2_EVP_PROXY_ENDPOINT)));
      final BackendApiFactory factory =
          new BackendApiFactory(
              Config.get(), sharedCommunicationObjects(discovery, agent.url("/")));
      final BackendApi api = factory.createBackendApi(Intake.EVENT_PLATFORM, null, false);

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
    private final Set<String> evpProxyEndpoints;

    private FakeFeaturesDiscovery(
        final String evpProxyEndpoint, final Set<String> evpProxyEndpoints) {
      super(
          new OkHttpClient(),
          Monitoring.DISABLED,
          HttpUrl.get("http://localhost:8126/"),
          ProtocolVersion.V0_5,
          true,
          false);
      this.evpProxyEndpoint = evpProxyEndpoint;
      this.evpProxyEndpoints = evpProxyEndpoints;
    }

    @Override
    public void discoverIfOutdated() {}

    @Override
    public String getEvpProxyEndpoint() {
      return evpProxyEndpoint;
    }

    @Override
    public boolean supportsEvpProxyEndpoint(final String endpoint) {
      return evpProxyEndpoints.contains(endpoint) || evpProxyEndpoints.contains("/" + endpoint);
    }

    @Override
    public boolean supportsEvpProxy() {
      return evpProxyEndpoint != null;
    }
  }
}
