package com.datadog.debugger.poller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static utils.TestHelper.getFixtureContent;

import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionSpec;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PollerHttpClientTest {
  public static final String URL_PATH = "/foo";

  private static final String API_KEY = "1c0ffee11c0ffee11c0ffee11c0ffee1";
  private static final Moshi MOSHI = new Moshi.Builder().build();

  @Mock Config config;

  final MockWebServer server = new MockWebServer();
  HttpUrl url;
  PollerHttpClient pollerHttpClient;
  Duration timeout = Duration.ofSeconds(1);
  Request request;

  @BeforeEach
  public void setUp() {
    url = server.url(URL_PATH);
    when(config.getRuntimeId()).thenReturn(UUID.randomUUID().toString());
    request = PollerRequestFactory.newConfigurationRequest(config, url.toString(), MOSHI);
  }

  @AfterEach
  public void tearDown() {
    if (pollerHttpClient != null) {
      pollerHttpClient.stop();
    }
    try {
      server.shutdown();
    } catch (final IOException e) {
      // Looks like this happens for some unclear reason, but should not affect tests
    }
  }

  @Test
  public void receive200() throws IOException, URISyntaxException {
    String content = getFixtureContent("/test_probe.json");
    server.enqueue(new MockResponse().setResponseCode(200).setBody(content));
    pollerHttpClient = new PollerHttpClient(request, timeout);
    try (Response response = pollerHttpClient.fetchConfiguration()) {
      assertEquals(200, response.code());
      assertEquals(content, response.body().string());
    }
  }

  @Test
  public void getRequest() {
    pollerHttpClient = new PollerHttpClient(request, timeout);
    assertEquals(request, pollerHttpClient.getRequest());
  }

  @Test
  public void receive500() {
    server.enqueue(new MockResponse().setResponseCode(500));
    pollerHttpClient = new PollerHttpClient(request, timeout);
    try (Response response = pollerHttpClient.fetchConfiguration()) {
      assertEquals(500, response.code());
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  @Test
  public void httpsUsesMultipleConnectionSpecs() {
    Request httpRequest =
        PollerRequestFactory.newConfigurationRequest(config, "https://127.0.0.1/", MOSHI);
    pollerHttpClient = new PollerHttpClient(httpRequest, Duration.ofSeconds(1));
    assertTrue(pollerHttpClient.getClient().connectionSpecs().size() > 1);
  }

  @Test
  public void httpForcesClearText() {
    Request httpRequest =
        PollerRequestFactory.newConfigurationRequest(config, "http://127.0.0.1/", MOSHI);
    pollerHttpClient = new PollerHttpClient(httpRequest, Duration.ofSeconds(1));
    assertEquals(1, pollerHttpClient.getClient().connectionSpecs().size());
    assertTrue(pollerHttpClient.getClient().connectionSpecs().contains(ConnectionSpec.CLEARTEXT));
  }

  private RecordedRequest getRecordedRequest(String env, String version)
      throws InterruptedException, IOException, URISyntaxException {
    server.enqueue(
        new MockResponse().setResponseCode(200).setBody(getFixtureContent("/test_probe.json")));
    when(config.getEnv()).thenReturn(env);
    when(config.getVersion()).thenReturn(version);
    request = PollerRequestFactory.newConfigurationRequest(config, url.toString(), MOSHI);
    pollerHttpClient = new PollerHttpClient(request, timeout);
    try (Response response = pollerHttpClient.fetchConfiguration()) {
      assertEquals(200, response.code());
    } catch (IOException e) {
      e.printStackTrace();
    }
    return server.takeRequest(1, TimeUnit.SECONDS);
  }
}
