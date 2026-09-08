package datadog.telemetry;

import static datadog.telemetry.TelemetryClient.Result.FAILURE;
import static datadog.telemetry.TelemetryClient.Result.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.HttpRetryPolicy;
import datadog.telemetry.api.RequestType;
import java.io.IOException;
import java.io.InterruptedIOException;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class TelemetryRouterTest {

  private static final HttpUrl AGENT_URL = HttpUrl.get("https://agent.example.com");
  private static final HttpUrl AGENT_TELEMETRY_URL =
      AGENT_URL.resolve("telemetry/proxy/api/v2/apmtelemetry");
  private static final HttpUrl INTAKE_URL = HttpUrl.get("https://intake.example.com");
  private static final String API_KEY = "api-key";
  private static final String API_KEY_HEADER = "DD-API-KEY";

  private final OkHttpClient okHttpClient = mock(OkHttpClient.class);
  private final DDAgentFeaturesDiscovery ddAgentFeaturesDiscovery =
      mock(DDAgentFeaturesDiscovery.class);

  private TelemetryClient agentTelemetryClient;
  private TelemetryClient intakeTelemetryClient;
  private TelemetryRouter router;

  @BeforeEach
  void setup() {
    agentTelemetryClient =
        TelemetryClient.buildAgentClient(
            okHttpClient, AGENT_URL, HttpRetryPolicy.Factory.NEVER_RETRY);
    intakeTelemetryClient =
        new TelemetryClient(okHttpClient, HttpRetryPolicy.Factory.NEVER_RETRY, INTAKE_URL, API_KEY);
    router =
        new TelemetryRouter(
            ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, false);
  }

  private TelemetryRequest dummyRequest() {
    return new TelemetryRequest(
        mock(EventSource.class), mock(EventSink.class), 1000, RequestType.APP_STARTED, false);
  }

  private static Call mockCall(int code) throws IOException {
    Call call = mock(Call.class);
    when(call.execute())
        .thenReturn(
            new Response.Builder()
                .request(new Request.Builder().url(HttpUrl.get("https://example.com")).build())
                .protocol(Protocol.HTTP_1_1)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("text/plain"), "OK"))
                .code(code)
                .build());
    return call;
  }

  // stubs newCall() via doAnswer rather than when().thenAnswer() so that re-stubbing across
  // multiple stages of the same test does not trigger a previously configured throwing answer
  private void stubNewCallReturning(int code) {
    doAnswer(invocation -> mockCall(code)).when(okHttpClient).newCall(any());
  }

  private void stubNewCallCapturingRequest(Request[] capturedRequest, int code) {
    doAnswer(
            invocation -> {
              capturedRequest[0] = invocation.getArgument(0);
              return mockCall(code);
            })
        .when(okHttpClient)
        .newCall(any());
  }

  private void stubNewCallThrowing(IOException exception) {
    doAnswer(
            invocation -> {
              throw exception;
            })
        .when(okHttpClient)
        .newCall(any());
  }

  @TableTest({
    "scenario                          | httpCode | sendResult",
    "informational status is a failure | 100      | FAILURE   ",
    "accepted status is a success      | 202      | SUCCESS   ",
    "not found status is not found     | 404      | NOT_FOUND ",
    "server error status is a failure  | 500      | FAILURE   "
  })
  void mapAnHttpStatusCodeToTheCorrectSendResult(int httpCode, String sendResult) {
    stubNewCallReturning(httpCode);

    TelemetryClient.Result result = router.sendRequest(dummyRequest());

    assertEquals(TelemetryClient.Result.valueOf(sendResult), result);
    verify(okHttpClient, times(1)).newCall(any());
  }

  @Test
  void catchIOExceptionFromOkHttpClientAndReturnFailure() {
    stubNewCallThrowing(new IOException("exception"));

    TelemetryClient.Result result = router.sendRequest(dummyRequest());

    assertEquals(FAILURE, result);
    verify(okHttpClient, times(1)).newCall(any());
  }

  @Test
  void catchInterruptedIOExceptionFromOkHttpClientAndReturnInterrupted() {
    stubNewCallThrowing(new InterruptedIOException("interrupted"));

    TelemetryClient.Result result = router.sendRequest(dummyRequest());

    assertEquals(TelemetryClient.Result.INTERRUPTED, result);
    verify(okHttpClient, times(1)).newCall(any());
  }

  @TableTest({
    "returnCode",
    "200       ",
    "404       ",
    "500       "
  })
  void keepTryingToSendTelemetryToAgentDespiteOfReturnCodeWhenIntakeClientIsNull(int returnCode) {
    TelemetryRouter agentOnlyRouter =
        new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, null, false);
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, returnCode);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true);

    agentOnlyRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    agentOnlyRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
  }

  @TableTest({
    "returnCode",
    "404       ",
    "500       "
  })
  void switchToIntakeWhenAgentStopsSupportingTelemetryProxyAndTelemetryRequestsStartFailing(
      int returnCode) {
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, returnCode);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
  }

  @Test
  void whenConfiguredToPreferIntakeUseIntakeClientFromTheStart() {
    TelemetryRouter telemetryRouter =
        new TelemetryRouter(
            ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true);
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, 200);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    telemetryRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
  }

  @Test
  void
      whenConfiguredToPreferIntakeDoNotSwitchToAgentIfIntakeRequestSucceedsEvenIfAgentSupportsTelemetryProxy() {
    TelemetryRouter telemetryRouter =
        new TelemetryRouter(
            ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true);
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, 200);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true);

    telemetryRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    telemetryRouter.sendRequest(dummyRequest());

    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
  }

  @Test
  void whenConfiguredToPreferIntakeDoNotSwitchToAgentIfRequestIsInterrupted() {
    TelemetryRouter telemetryRouter =
        new TelemetryRouter(
            ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true);
    Request[] capturedRequest = new Request[1];
    doAnswer(
            invocation -> {
              capturedRequest[0] = invocation.getArgument(0);
              throw new InterruptedIOException("interrupted");
            })
        .when(okHttpClient)
        .newCall(any());
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true);

    telemetryRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    stubNewCallCapturingRequest(capturedRequest, 200);

    telemetryRouter.sendRequest(dummyRequest());

    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
  }

  @Test
  void whenConfiguredToPreferIntakeSwitchToAgentIfIntakeRequestFails() {
    TelemetryRouter telemetryRouter =
        new TelemetryRouter(
            ddAgentFeaturesDiscovery, agentTelemetryClient, intakeTelemetryClient, true);
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, 403);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true);

    telemetryRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    stubNewCallCapturingRequest(capturedRequest, 200);

    telemetryRouter.sendRequest(dummyRequest());

    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
  }

  @Test
  void doNotSwitchToIntakeWhenAgentStopsSupportingTelemetryProxyButAcceptsTelemetryRequests() {
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, 200);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    stubNewCallCapturingRequest(capturedRequest, 201);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
  }

  @TableTest({
    "returnCode",
    "404       ",
    "500       "
  })
  void switchToIntakeWhenAgentFailsToReceiveTelemetryRequests(int returnCode) {
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, returnCode);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true, false);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
  }

  @TableTest({
    "returnCode | expectedApiKey | expectedUrlIsAgent",
    "404        |                | true              ",
    "500        |                | true              "
  })
  void useAgentWhenIntakeIsNotAvailable(
      int returnCode, String expectedApiKey, boolean expectedUrlIsAgent) {
    HttpUrl expectedUrl = expectedUrlIsAgent ? AGENT_TELEMETRY_URL : INTAKE_URL;
    TelemetryRouter agentOnlyRouter =
        new TelemetryRouter(ddAgentFeaturesDiscovery, agentTelemetryClient, null, false);
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, returnCode);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    agentOnlyRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(expectedUrl, capturedRequest[0].url());
    assertEquals(expectedApiKey, capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    agentOnlyRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    agentOnlyRouter.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(expectedUrl, capturedRequest[0].url());
    assertEquals(expectedApiKey, capturedRequest[0].header(API_KEY_HEADER));
  }

  @TableTest({
    "returnCode",
    "404       ",
    "500       "
  })
  void switchToIntakeThenBackToAgentWhenBothFailToReceiveTelemetryRequests(int returnCode) {
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, returnCode);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    // always send first telemetry request to Agent
    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    // switch to Intake if sending a telemetry request to Agent failed or Agent supports
    // telemetry proxy
    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    // switch back to Agent if Intake request fails
    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
  }

  @Test
  void singleClientConstructorSkipsFeatureDiscoveryAndDelegatesToTheGivenClient() {
    TelemetryClient singleClient = mock(TelemetryClient.class);
    TelemetryRouter singleClientRouter = new TelemetryRouter(singleClient);
    when(singleClient.sendHttpRequest(any())).thenReturn(SUCCESS);

    TelemetryClient.Result result = singleClientRouter.sendRequest(dummyRequest());

    assertEquals(SUCCESS, result);
    verify(singleClient, times(1)).sendHttpRequest(any());
    verify(ddAgentFeaturesDiscovery, never()).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, never()).supportsTelemetryProxy();
  }

  @Test
  void singleClientConstructorDoesNotSwitchClientsOnFailure() {
    TelemetryClient singleClient = mock(TelemetryClient.class);
    TelemetryRouter singleClientRouter = new TelemetryRouter(singleClient);

    // first request fails
    when(singleClient.sendHttpRequest(any())).thenReturn(FAILURE);
    TelemetryClient.Result firstResult = singleClientRouter.sendRequest(dummyRequest());

    assertEquals(FAILURE, firstResult);
    verify(singleClient, times(1)).sendHttpRequest(any());

    // second request goes to the same client
    when(singleClient.sendHttpRequest(any())).thenReturn(SUCCESS);
    TelemetryClient.Result secondResult = singleClientRouter.sendRequest(dummyRequest());

    assertEquals(SUCCESS, secondResult);
    verify(singleClient, times(2)).sendHttpRequest(any());
    verifyNoMoreInteractions(ddAgentFeaturesDiscovery);
  }

  @TableTest({
    "returnCode",
    "404       ",
    "500       "
  })
  void switchBackToAgentIfItStartsSupportingTelemetry(int returnCode) {
    Request[] capturedRequest = new Request[1];
    stubNewCallCapturingRequest(capturedRequest, returnCode);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    // always send first telemetry request to Agent
    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    // switch to Intake if sending a telemetry request to Agent failed or Agent supports
    // telemetry proxy
    stubNewCallCapturingRequest(capturedRequest, 201);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(true);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(INTAKE_URL, capturedRequest[0].url());
    assertEquals(API_KEY, capturedRequest[0].header(API_KEY_HEADER));
    clearInvocations(okHttpClient, ddAgentFeaturesDiscovery);

    // switch back to Agent if it starts supporting telemetry proxy
    stubNewCallCapturingRequest(capturedRequest, returnCode);
    when(ddAgentFeaturesDiscovery.supportsTelemetryProxy()).thenReturn(false);

    router.sendRequest(dummyRequest());

    verify(ddAgentFeaturesDiscovery, times(1)).discoverIfOutdated();
    verify(ddAgentFeaturesDiscovery, times(1)).supportsTelemetryProxy();
    verify(okHttpClient, times(1)).newCall(any());
    assertEquals(AGENT_TELEMETRY_URL, capturedRequest[0].url());
    assertNull(capturedRequest[0].header(API_KEY_HEADER));
  }
}
