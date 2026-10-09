package com.datadog.featureflag;

import static com.datadog.featureflag.FeatureFlagEventType.EXPOSURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.api.ProtocolVersion;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class FeatureFlagTransportRegressionTest {
  @TableTest({
    "Scenario              | First Attempt | Attempts | Delivered",
    "refused then accepted | refused       | 2        | true     ",
    "refused throughout    | refused-all   | 4        | false    ",
    "forbidden             | 403           | 1        | false    ",
    "throttled             | 429           | 1        | false    ",
    "internal server error | 500           | 1        | false    ",
    "unavailable           | 503           | 1        | false    ",
    "read timeout          | timeout       | 1        | false    ",
    "reset                 | reset         | 1        | false    ",
    "broken pipe           | broken-pipe   | 1        | false    "
  })
  void remoteConfigRetriesOnlyProvenPreSendFailures(
      String firstAttempt, int attempts, boolean delivered) throws Exception {
    final List<Request> requests = new ArrayList<>();
    final SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentUrl = HttpUrl.get("http://localhost:8126/");
    sco.agentHttpClient =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  final Request request = chain.request();
                  requests.add(request);
                  if (firstAttempt.equals("refused-all")) {
                    throw new ConnectException("Connection refused");
                  }
                  if (requests.size() == 1) {
                    switch (firstAttempt) {
                      case "refused":
                        throw new ConnectException("Connection refused");
                      case "timeout":
                        throw new SocketTimeoutException("read timeout");
                      case "reset":
                        throw new SocketException("Connection reset");
                      case "broken-pipe":
                        throw new SocketException("Broken pipe");
                      default:
                        return response(request, Integer.parseInt(firstAttempt), "{}");
                    }
                  }
                  return response(request, 202, "{}");
                })
            .build();
    final BackendApi api =
        new FeatureFlagBackendApiFactory(config("remote_config"), sco, EXPOSURE).create();

    if (delivered) {
      post(api);
    } else {
      assertThrows(IOException.class, () -> post(api));
    }

    assertEquals(attempts, requests.size());
    for (Request request : requests) {
      assertEquals("/evp_proxy/v2/api/v2/exposures", request.url().encodedPath());
      assertNull(request.header("DD-API-KEY"));
    }
  }

  @Test
  void agentlessWithoutIdentityKeepsAnOlderAgentUsableWithoutDirectCredentials() throws Exception {
    final List<Request> requests = new ArrayList<>();
    final SharedCommunicationObjects sco = new SharedCommunicationObjects();
    sco.agentUrl = HttpUrl.get("http://localhost:8126/");
    sco.agentHttpClient =
        new OkHttpClient.Builder()
            .addInterceptor(
                chain -> {
                  final Request request = chain.request();
                  requests.add(request);
                  return response(
                      request,
                      200,
                      request.url().encodedPath().equals("/info")
                          ? "{\"endpoints\":[\"v0.5/traces\",\"evp_proxy/v4/\"],\"evp_proxy_allowed_headers\":[]}"
                          : "{}");
                })
            .build();

    final BackendApi api =
        new FeatureFlagBackendApiFactory(config("agentless"), sco, EXPOSURE).create();
    post(api);

    assertEquals(2, requests.size());
    assertEquals("/info", requests.get(0).url().encodedPath());
    assertEquals("/evp_proxy/v4/api/v2/exposures", requests.get(1).url().encodedPath());
    assertNull(requests.get(1).header("DD-API-KEY"));
    assertNull(requests.get(1).header("DD-EVP-ORIGIN"));
  }

  private static Config config(String source) {
    final Config config = mock(Config.class);
    when(config.getFeatureFlaggingConfigurationSource()).thenReturn(source);
    when(config.getIdGenerationStrategy()).thenReturn(Config.get().getIdGenerationStrategy());
    when(config.getProtocolVersion()).thenReturn(ProtocolVersion.V0_5);
    return config;
  }

  private static Response response(Request request, int code, String body) {
    return new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(ResponseBody.create(null, body))
        .build();
  }

  private static void post(BackendApi api) throws IOException {
    api.post(
        "exposures",
        RequestBody.create(MediaType.parse("application/json"), "{}"),
        stream -> null,
        null,
        false);
  }
}
