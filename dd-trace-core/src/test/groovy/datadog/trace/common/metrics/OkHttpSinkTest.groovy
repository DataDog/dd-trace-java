package datadog.trace.common.metrics

import datadog.trace.test.util.DDSpecification
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import spock.lang.Requires

import java.nio.ByteBuffer

import static datadog.trace.api.Platform.isJavaVersionAtLeast
import static datadog.trace.common.metrics.EventListener.EventType.BAD_PAYLOAD
import static datadog.trace.common.metrics.EventListener.EventType.DOWNGRADED
import static datadog.trace.common.metrics.EventListener.EventType.ERROR
import static datadog.trace.common.metrics.EventListener.EventType.OK

@Requires({ isJavaVersionAtLeast(8) })
class OkHttpSinkTest extends DDSpecification {

  def "http status code #responseCode yields #eventType"() {
    setup:
    String agentUrl = "http://localhost:8126"
    String path = "v0.5/stats"
    EventListener listener = Mock(EventListener)
    OkHttpClient client = Mock(OkHttpClient)

    OkHttpSink sink = new OkHttpSink(client, agentUrl, path)
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
