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
  .request(REQUEST).protocol(Protocol.HTTP_1_0).message("msg").code(500).build()
  private static final Response NOT_FOUND_RESPONSE = new Response.Builder()
  .request(REQUEST).protocol(Protocol.HTTP_1_0).message("msg").code(404).build()

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
    setup:
    def queue = new ArrayDeque<>([REQUEST])
    Call call = Mock()

    when:
    t.start()
    t.join()

    then:
    1 * telemetryService.addConfiguration(_)
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.addStartedRequest()

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.prepareRequests() >> queue
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> OK_RESPONSE
    queue.size() == 0

    then:
    1 * telemetryService.getHeartbeatInterval() >> 10_000L
    1 * sleeper.sleep(10_000L) >> { t.interrupt() }

    then:
    1 * telemetryService.appClosingRequest() >> REQUEST
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> OK_RESPONSE
    0 * _
  }

  void 'one loop run with two requests'() {
    setup:
    def request1 = new Request.Builder()
      .url('https://example.com/1').build()
    def request2 = new Request.Builder()
      .url('https://example.com/2').build()
    def request3 = new Request.Builder()
      .url('https://example.com/3').build()
    def queue = new ArrayDeque<>([request1, request2])
    Call call1 = Mock()
    Call call2 = Mock()
    Call call3 = Mock()

    when:
    t.start()
    t.join()

    then:
    1 * telemetryService.addConfiguration(_)
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.addStartedRequest()

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.prepareRequests() >> queue
    1 * okHttpClient.newCall(request1) >> call1
    1 * call1.execute() >> OK_RESPONSE
    1 * okHttpClient.newCall(request2) >> call2
    1 * call2.execute() >> OK_RESPONSE
    queue.size() == 0

    then:
    1 * telemetryService.getHeartbeatInterval() >> 10_000L
    1 * sleeper.sleep(10_000L) >> { t.interrupt() }

    then:
    1 * telemetryService.appClosingRequest() >> request3
    1 * okHttpClient.newCall(request3) >> call3
    1 * call3.execute() >> OK_RESPONSE
    0 * _
  }

  void 'endpoint not found'() {
    setup:
    def queue = new ArrayDeque<>([REQUEST, REQUEST])
    Call call = Mock()

    when:
    t.start()
    t.join()

    then:
    1 * telemetryService.addConfiguration(_)
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.addStartedRequest()

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.prepareRequests() >> queue
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> NOT_FOUND_RESPONSE
    queue.size() == 0

    then:
    1 * telemetryService.getHeartbeatInterval() >> 10_000L
    1 * sleeper.sleep(10_000L) >> { t.interrupt() }

    then:
    1 * telemetryService.appClosingRequest() >> REQUEST
    1 * okHttpClient.newCall(REQUEST) >> call
    1 * call.execute() >> OK_RESPONSE
    0 * _
  }

  void 'backoff time increases'() {
    setup:
    def queue = new ArrayDeque<>([REQUEST])
    Call call = Mock()

    when:
    t.start()
    t.join()

    then:
    1 * telemetryService.addConfiguration(_)
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.addStartedRequest()

    then:
    1 * periodicAction.doIteration(telemetryService)
    1 * telemetryService.prepareRequests() >> queue

    then:
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
    0 * _
  }
}
