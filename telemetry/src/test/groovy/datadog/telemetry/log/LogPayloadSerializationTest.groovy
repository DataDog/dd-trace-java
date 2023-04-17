package datadog.telemetry.log

import com.squareup.moshi.Moshi
import datadog.telemetry.RequestBuilder
import datadog.telemetry.api.LogMessage
import datadog.telemetry.api.LogMessageLevel
import datadog.telemetry.api.Logs
import datadog.telemetry.api.Payload
import datadog.telemetry.api.RequestType
import okhttp3.HttpUrl
import okhttp3.Request
import okio.Buffer
import spock.lang.Specification

class LogPayloadSerializationTest extends Specification{

  void 'Log Payload serialization'() {
    setup:
    LogMessage log = new LogMessage()
    log.setMessage("debug message")
    log.setLevel(LogMessageLevel.ERROR)

    when:
    String json =(new Moshi.Builder()).build().adapter(LogMessage).toJson(log)
    System.out.println(json)

    then:
    json == '{"level":"ERROR","message":"debug message"}'
  }

  void 'Log Payload request building'(){
    setup:
    RequestBuilder reqBuilder = new RequestBuilder(HttpUrl.get('https://example.com'))

    LogMessage log1 = new LogMessage()
    log1.setMessage("debug message 1")
    log1.setLevel(LogMessageLevel.ERROR)

    LogMessage log2 = new LogMessage()
    log2.setMessage("debug message 2")
    log2.setLevel(LogMessageLevel.ERROR)

    when:
    Payload payload = new Logs().messages(Arrays.asList(log1, log2))
    Request request = reqBuilder.build(RequestType.LOGS, payload.requestType(RequestType.LOGS))
    System.out.println(request.body())
    final Buffer buffer = new Buffer()
    request.body().writeTo(buffer)
    String body = buffer.readUtf8()
    System.out.println("Body: " + body)

    then:
    body.contains("\"payload\":{\"logs\":[{\"level\":\"ERROR\",\"message\":\"debug message 1\"},{\"level\":\"ERROR\",\"message\":\"debug message 2\"}],\"request_type\":\"logs\"}")
  }
}