package datadog.smoketest


import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

import java.util.concurrent.TimeUnit

class ProfilingIntegrationBogusApiKeyTest extends AbstractProfilingIntegrationTest {

  // This needs to give enough time for test app to start up and recording to happen
  private static final int REQUEST_WAIT_TIMEOUT = 40

  @Override
  def getExitDelay() {
    return PROFILING_START_DELAY_SECONDS + PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS * 2 + 1
  }

  def "test that profiling doesn't start with bogus api key"() {
    setup:
    profilingServer.enqueue(new MockResponse().setResponseCode(200))

    when:
    RecordedRequest request = profilingServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS)
    checkLog()

    then:
    !logHasErrors
    // No request expected since profiling was disabled due to bogus api key
    request == null
  }

  String apiKey() {
    return "bogus"
  }
}
