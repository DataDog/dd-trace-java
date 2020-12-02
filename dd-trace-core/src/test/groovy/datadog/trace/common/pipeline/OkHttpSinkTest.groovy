package datadog.trace.common.pipeline

import datadog.trace.test.util.DDSpecification
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

import java.nio.ByteBuffer

import static datadog.trace.common.pipeline.EventListener.EventType.BAD_PAYLOAD
import static datadog.trace.common.pipeline.EventListener.EventType.DOWNGRADED
import static datadog.trace.common.pipeline.EventListener.EventType.ERROR
import static datadog.trace.common.pipeline.EventListener.EventType.OK

class OkHttpSinkTest extends DDSpecification {

  def "http status code #responseCode yields #eventType"() {
    setup:
    String agentUrl = "http://localhost:8126"
    String path = "v0.5/stats"
    EventListener listener = Mock(EventListener)
    OkHttpClient client = Mock(OkHttpClient)

    OkHttpSink sink = new OkHttpSink(client, agentUrl, path)
    sink.register(listener)

    when: "first interaction discovers endpoint"
    sink.accept(0, ByteBuffer.allocate(0))

    then:
    // one discovery
    1 * client.newCall({it.method() == "GET"}) >> { Request request -> respond(request, 400) }
    // one send
    1 * client.newCall({it.method() == "PUT"}) >> { Request request -> respond(request, 200) }
    1 * listener.onEvent(OK, _)

    when: "request after initial discovery"
    sink.accept(0, ByteBuffer.allocate(0))

    then:
    if (responseCode == 404) {
      // will try to discover again
      1 * client.newCall({it.method() == "GET"}) >> { Request request -> respond(request, responseCode) }
    }
    1 * client.newCall({it.method() == "PUT"}) >> { Request request -> respond(request, responseCode) }
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

}
