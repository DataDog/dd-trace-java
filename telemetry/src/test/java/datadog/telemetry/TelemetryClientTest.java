package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.communication.http.HttpRetryPolicy;
import datadog.telemetry.api.RequestType;
import datadog.trace.api.Config;
import java.net.ConnectException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class TelemetryClientTest {

  @TableTest({
    "scenario                            | ciVisEnabled | ciVisAgentlessEnabled | ciVisAgentlessUrl                  | expectedUrl                                                 ",
    "ci visibility agentless enabled     | true         | true                  | http://ci.visibility.agentless.url | http://ci.visibility.agentless.url/api/v2/apmtelemetry      ",
    "ci visibility disabled              | false        | true                  | http://ci.visibility.agentless.url | https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry",
    "ci visibility agentless disabled    | true         | false                 | http://ci.visibility.agentless.url | https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry",
    "ci visibility agentless url is null | true         | true                  |                                    | https://all-http-intake.logs.datad0g.com/api/v2/apmtelemetry"
  })
  void intakeClientUsesCiVisibilityAgentlessUrlIfConfiguredToDoSo(
      boolean ciVisEnabled,
      boolean ciVisAgentlessEnabled,
      String ciVisAgentlessUrl,
      String expectedUrl) {
    Config config = spy(Config.get());
    doReturn("dummy-key").when(config).getApiKey();
    doReturn(123).when(config).getAgentTimeout();
    doReturn("datad0g.com").when(config).getSite();
    doReturn(ciVisEnabled).when(config).isCiVisibilityEnabled();
    doReturn(ciVisAgentlessEnabled).when(config).isCiVisibilityAgentlessEnabled();
    doReturn(ciVisAgentlessUrl).when(config).getCiVisibilityAgentlessUrl();

    TelemetryClient intakeClient =
        TelemetryClient.buildIntakeClient(config, HttpRetryPolicy.Factory.NEVER_RETRY);

    assertEquals(expectedUrl, intakeClient.getUrl().toString());
  }

  @Test
  void intakeClientRetriesTelemetryRequestIfConfiguredToDoSo() {
    OkHttpClient httpClient = mock(OkHttpClient.class);
    HttpRetryPolicy.Factory httpRetryPolicy = new HttpRetryPolicy.Factory(2, 50, 1.5, true);
    HttpUrl httpUrl = HttpUrl.get("https://intake.example.com");
    TelemetryClient intakeClient =
        new TelemetryClient(httpClient, httpRetryPolicy, httpUrl, "dummy-api-key");

    doAnswer(
            invocation -> {
              throw new ConnectException("exception");
            })
        .when(httpClient)
        .newCall(any());

    intakeClient.sendHttpRequest(dummyRequest());

    // original request + 2 retries
    verify(httpClient, times(3)).newCall(any());
  }

  private Request.Builder dummyRequest() {
    return new TelemetryRequest(
            mock(EventSource.class), mock(EventSink.class), 1000, RequestType.APP_STARTED, false)
        .httpRequest();
  }
}
