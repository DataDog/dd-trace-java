package datadog.telemetry.log

import com.squareup.moshi.Moshi
import datadog.telemetry.RequestBuilder
import datadog.telemetry.api.RequestType
import datadog.trace.api.telemetry.TelemetryLogEntry
import okhttp3.HttpUrl
import okhttp3.Request
import okio.Buffer
import spock.lang.Specification

class LogPayloadSerializationTest extends Specification{

  void 'Log Payload serialization'() {
    setup:
    TelemetryLogEntry log = new TelemetryLogEntry()
    log.setMessage("debug message")
    log.setLevel("ERROR")

    when:
    String json =(new Moshi.Builder()).build().adapter(TelemetryLogEntry).toJson(log)
    System.out.println(json)

    then:
    json == '{"level":"ERROR","message":"debug message"}'
  }

  void 'Log Payload request building'(){
    setup:
    RequestBuilder reqBuilder = new RequestBuilder(HttpUrl.get('https://example.com'))

    TelemetryLogEntry log1 = new TelemetryLogEntry()
    log1.setMessage("debug message 1")
    log1.setLevel("ERROR")

    TelemetryLogEntry log2 = new TelemetryLogEntry()
    log2.setMessage("debug message 2")
    log2.setLevel("ERROR")

    when:
    Request request = reqBuilder.logBuild(RequestType.LOGS, Arrays.asList(log1, log2))
    System.out.println(request.body())
    final Buffer buffer = new Buffer()
    request.body().writeTo(buffer)
    String body = buffer.readUtf8()
    System.out.println("Body: " + body)

    then:
    body.contains("\"payload\":[{\"level\":\"ERROR\",\"message\":\"debug message 1\"},{\"level\":\"ERROR\",\"message\":\"debug message 2\"}],\"request_type\":\"logs\"")
  }
}
