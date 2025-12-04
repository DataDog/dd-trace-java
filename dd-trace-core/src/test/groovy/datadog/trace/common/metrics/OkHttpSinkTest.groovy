package datadog.trace.common.metrics

import datadog.trace.test.util.DDSpecification
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

import static datadog.trace.common.metrics.EventListener.EventType.BAD_PAYLOAD
import static datadog.trace.common.metrics.EventListener.EventType.DOWNGRADED
import static datadog.trace.common.metrics.EventListener.EventType.ERROR
import static datadog.trace.common.metrics.EventListener.EventType.OK
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V6_METRICS_ENDPOINT

class OkHttpSinkTest extends DDSpecification {

  def "http status code #responseCode yields #eventType"() {
    setup:
    String agentUrl = "http://localhost:8126"
    String path = V6_METRICS_ENDPOINT
    EventListener listener = Mock(EventListener)
    OkHttpClient client = Mock(OkHttpClient)
    OkHttpSink sink = new OkHttpSink(client, agentUrl, path, true, false, Collections.emptyMap())
    sink.register(listener)

    when:
    sink.accept(0, ByteBuffer.allocate(0))

    then:
    1 * client.newCall(_) >> { Request request -> respond(request, responseCode) }
    1 * listener.onEvent(eventType, _)

    where:
    eventType   | responseCode
    DOWNGRADED  | 404
    ERROR       | 500
    ERROR       | 0 // throw
    BAD_PAYLOAD | 400
    OK          | 200
    OK          | 201
  }

  def "degrade to async mode when agent slow to respond"() {
    // metrics payloads are relatively large and we don't want to copy them,
    // and we typically expect the agent to respond well within the aggregation
    // window, so will send synchronously whenever possible to avoid allocating
    // a copy of the payload. When the agent is slow to respond, we degrade to
    // an asynchronous mode where up to 100 seconds of requests are copied and
    // enqueued for sending in the background, because we don't want to lose
    // them if it's possible not to.
    setup:
    String agentUrl = "http://localhost:8126"
    String path = V6_METRICS_ENDPOINT
    CountDownLatch latch = new CountDownLatch(2)
    EventListener listener = new BlockingListener(latch)
    OkHttpClient client = Mock(OkHttpClient)
    OkHttpSink sink = new OkHttpSink(client, agentUrl, path, true, false, Collections.emptyMap())
    sink.register(listener)
    AtomicBoolean first = new AtomicBoolean(true)

    when: "one slow response followed by a request"
    sink.accept(1, ByteBuffer.allocate(0))
    sink.accept(1, ByteBuffer.allocate(0))
    latch.await()
    then: "the second request degrades to async mode"
    2 * client.newCall(_) >> { Request request ->
      if (first.compareAndSet(true, false)) {
        Thread.sleep(1001)
      } else {
        assert sink.isInDegradedMode()
      }
      respond(request, 200)
    }
    listener.events.size() == 2
    for (EventListener.EventType eventType : listener.events) {
      assert eventType == OK
    }
    long asyncRequests = sink.asyncRequestCount()
    asyncRequests == 1
    sink.isInDegradedMode()
    when: "the agent has recovered and has responded quickly once"
    sink.accept(1, ByteBuffer.allocate(0))
    then: "the request was sent synchronously"
    1 * client.newCall(_) >> { Request request -> respond(request, 200) }
    asyncRequests == sink.asyncRequestCount()
    !sink.isInDegradedMode()
  }

  def respond(Request request, int code) {
    if (0 == code) {
      return error(request)
    }
    return Mock(Call) {
      it.execute() >> new Response.Builder()
        .code(code)
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .message("message")
        .body(ResponseBody.create(MediaType.get("text/plain"), "message"))
        .build()
    }
  }

  def error(Request request) {
    return Mock(Call) {
      it.execute() >> { throw new IOException("thrown by test") }
    }
  }

  class BlockingListener implements EventListener {

    private final CountDownLatch latch
    private List<EventType> events = new CopyOnWriteArrayList<>()

    BlockingListener(CountDownLatch latch) {
      this.latch = latch
    }

    @Override
    void onEvent(EventType eventType, String message) {
      events.add(eventType)
      latch.countDown()
    }
  }
}
