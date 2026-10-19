package datadog.communication;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.HttpRetryPolicy;
import datadog.trace.api.Config;
import datadog.trace.api.intake.Intake;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.tabletest.junit.TableTest;

/** Pins send-once on the clients that BackendApiFactory builds, for both EVP routes. */
class SendOnceTransportTest extends DDJavaSpecification {
  private MockWebServer server;
  private OkHttpClient agentClient;
  private OkHttpClient intakeClient;

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();
    agentClient = new OkHttpClient();
    // Direct URLs are fixed to https://event-platform-intake.<site>/. Send them to the local
    // server.
    intakeClient =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  final Request original = chain.request();
                  final HttpUrl local =
                      original
                          .url()
                          .newBuilder()
                          .scheme("http")
                          .host(server.getHostName())
                          .port(server.getPort())
                          .build();
                  return chain.proceed(original.newBuilder().url(local).build());
                })
            .build();
  }

  @AfterEach
  void tearDown() throws IOException {
    for (final OkHttpClient client : new OkHttpClient[] {agentClient, intakeClient}) {
      client.dispatcher().executorService().shutdownNow();
      client.connectionPool().evictAll();
    }
    server.shutdown();
  }

  @WithConfig(key = "API_KEY", value = "api-key", env = true)
  @TableTest({
    "Scenario         | Route  | Send Once | Requests | Second Post Fails",
    "proxy send-once  | proxy  | true      | 2        | true             ",
    "proxy control    | proxy  | false     | 3        | false            ",
    "direct send-once | direct | true      | 2        | true             ",
    "direct control   | direct | false     | 3        | false            "
  })
  void doesNotReplayAPostAfterThePooledConnectionDrops(
      final String route, final boolean sendOnce, final int requests, final boolean secondPostFails)
      throws Exception {
    // The first call leaves a pooled connection. The server drops it during the second call.
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
    final BackendApiFactory factory = factory(sendOnce);
    final BackendApi api =
        route.equals("proxy")
            ? factory.createEvpProxyApiForEndpoint(
                Intake.EVENT_PLATFORM,
                false,
                HttpRetryPolicy.Factory.NEVER_RETRY,
                V4_EVP_PROXY_ENDPOINT)
            : factory.createDirectIntakeApi(Intake.EVENT_PLATFORM, false, false);

    post(api);
    if (secondPostFails) {
      assertThrows(IOException.class, () -> post(api));
    } else {
      post(api);
    }

    assertEquals(requests, server.getRequestCount());
  }

  private BackendApiFactory factory(final boolean sendOnce) {
    final SharedCommunicationObjects sco =
        new SharedCommunicationObjects() {
          @Override
          public OkHttpClient getIntakeHttpClient() {
            return intakeClient;
          }
        };
    sco.agentUrl = server.url("/");
    sco.agentHttpClient = agentClient;
    return new BackendApiFactory(Config.get(), sco, sendOnce);
  }

  private static void post(final BackendApi api) throws IOException {
    api.post(
        "flagevaluation",
        RequestBody.create(MediaType.parse("application/json"), "{}"),
        stream -> null,
        null,
        false);
  }
}
