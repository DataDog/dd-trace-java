package datadog.communication.ddagent;

import static datadog.communication.http.OkHttpUtils.DATADOG_CONTAINER_TAGS_HASH;
import static datadog.trace.api.ProtocolVersion.V0_5;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.metrics.api.Monitoring;
import datadog.trace.api.BaseHash;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

// forked: the base hash is global, and this needs it unset
class DDAgentFeaturesDiscoveryBaseHashForkedTest {

  @Test
  void setsBaseHashWithoutContainerTagsHashUntilOneArrives() throws Exception {
    HttpUrl agentNotUp;
    try (MockWebServer agent = new MockWebServer()) {
      agentNotUp = agent.url("/");
    }
    assertNull(BaseHash.getBaseHashStr());
    discover(agentNotUp);
    assertEquals(BaseHash.calc(null), BaseHash.getBaseHash());

    try (MockWebServer agent = agent(null)) {
      discover(agent.url("/"));
    }
    assertEquals(BaseHash.calc(null), BaseHash.getBaseHash());

    try (MockWebServer agent = agent("test-hash")) {
      discover(agent.url("/"));
    }
    assertEquals(BaseHash.calc("test-hash"), BaseHash.getBaseHash());
  }

  private static MockWebServer agent(String containerTagsHash) {
    MockWebServer agent = new MockWebServer();
    agent.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            MockResponse response = new MockResponse().setBody("{}");
            return containerTagsHash == null
                ? response
                : response.setHeader(DATADOG_CONTAINER_TAGS_HASH, containerTagsHash);
          }
        });
    return agent;
  }

  // a new instance each time: discover() skips a run in the same millisecond as the last one
  private static void discover(HttpUrl agentUrl) {
    new DDAgentFeaturesDiscovery(
            new OkHttpClient(), Monitoring.DISABLED, agentUrl, V0_5, true, false)
        .discover();
  }
}
