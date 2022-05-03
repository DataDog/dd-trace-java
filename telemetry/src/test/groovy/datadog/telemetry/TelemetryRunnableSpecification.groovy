package datadog.telemetry

import datadog.trace.test.util.DDSpecification
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class TelemetryRunnableSpecification extends DDSpecification {
  private static final Request REQUEST = new Request.Builder()
  .url('https://example.com').build()
  private static final Response OK_RESPONSE = new Response.Builder()
  .request(REQUEST).protocol(Protocol.HTTP_1_0).message("msg").code(202).build()
  private static final Response BAD_RESPONSE = new Response.Builder()
  .request(REQUEST).protocol(Protocol.HTTP_1_0).message("msg").code(404).build()

  Call call = Mock()

  OkHttpClient okHttpClient = Mock()
  TelemetryRunnable.ThreadSleeper sleeper = Mock()
  TelemetryServiceImpl telemetryService = Mock()
  TelemetryRunnable.TelemetryPeriodicAction periodicAction = Mock()
  TelemetryRunnable runnable = new TelemetryRunnable(okHttpClient, telemetryService, [periodicAction], sleeper)
  Thread t = new Thread(runnable)

  void cleanup() {
    if (t.isAlive()) {
      t.interrupt()
      t.join()
    }
  }

  void 'one loop run with one request'() {
    def queue = new ArrayDeque<>([REQUEST])

    when:
    t.start()
    t.join()

    then:
    1 * telemetryService.addStartedRequest()

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.prepareRequests() >> queue
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> OK_RESPONSE
    queue.size() == 0

    then:
    1 * sleeper.sleep(10_000L) >> { t.interrupt() }

    then:
    1 * telemetryService.appClosingRequest() >> REQUEST
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> OK_RESPONSE
  }

  void 'backoff time increases'() {
    def queue = new ArrayDeque<>([REQUEST])

    when:
    t.start()
    t.join()

    then:
    1 * telemetryService.addStartedRequest()

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.prepareRequests() >> queue
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> BAD_RESPONSE
    queue.size() == 1

    then:
    1 * sleeper.sleep(3_000)

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.prepareRequests() >> queue
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> BAD_RESPONSE
    queue.size() == 1

    then:
    1 * sleeper.sleep(9_000) >> { t.interrupt() }

    then:
    1 * telemetryService.appClosingRequest() >> REQUEST
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> OK_RESPONSE
  }
}
