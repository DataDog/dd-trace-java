package datadog.smoketest


import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Ignore

import java.util.concurrent.TimeUnit

class ProfilingIntegrationShutdownTest extends AbstractProfilingIntegrationTest {

  // This needs to give enough time for test app to start up and recording to happen
  private static final int REQUEST_WAIT_TIMEOUT = 40

  // Run app enough time to get profiles
  private static final int RUN_APP_FOR = PROFILING_START_DELAY_SECONDS + PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS * 2 + 1

  @Override
  def getExitDelay() {
    return RUN_APP_FOR
  }

  @Ignore("flaky")
  def "test that profiling agent doesn't prevent app from exiting"() {
    setup:
    profilingServer.enqueue(new MockResponse().setResponseCode(200))

    when:
    RecordedRequest request = profilingServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS)

    checkLog()

    then:
    !logHasErrors
    request.bodySize > 0

    then:
    // Wait for the app exit with some extra time.
    // The expectation is that agent doesn't prevent app from exiting.
    testedProcess.waitFor(RUN_APP_FOR + 10, TimeUnit.SECONDS) == true
  }

}
