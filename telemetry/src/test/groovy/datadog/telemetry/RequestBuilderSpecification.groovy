package datadog.telemetry

import datadog.telemetry.api.AppStarted
import datadog.telemetry.api.Dependency
import datadog.telemetry.api.DependencyType
import datadog.telemetry.api.KeyValue
import datadog.telemetry.api.RequestType
import datadog.trace.test.util.DDSpecification
import groovy.json.JsonSlurper
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer

class RequestBuilderSpecification extends DDSpecification {
  RequestBuilder reqBuilder = new RequestBuilder(HttpUrl.get('https://example.com'))

  private final static JsonSlurper SLURPER = new JsonSlurper()

  private parseBody(RequestBody body) {
    Buffer buffer = new Buffer()
    body.writeTo(buffer)
    byte[] bytes = new byte[buffer.size()]
    buffer.read(bytes)
    SLURPER.parse(bytes)
  }

  void 'appStarted request'() {
    Request req
    def body

    when:
    AppStarted payload = new AppStarted(
      requestType: RequestType.APP_STARTED,
      _configuration: [new KeyValue(name: 'name', value: 'value')],
      dependencies: [
        new Dependency(
        hash: 'hash', name: 'name', type: DependencyType.SHAREDSYSTEMLIBRARY, version: '1.2.3')
      ]
      )
    req = reqBuilder.build(RequestType.APP_STARTED, payload)
    body = parseBody req.body()

    then:
    req.header('Content-type') == 'application/json; charset=utf-8'
    req.header('DD-Telemetry-API-Version') == 'v1'
    req.header('DD-Telemetry-Request-Type') == 'app-started'
    body['api_version'] == 'V1'
    with(body['application']) {
      language_name == 'jvm'
      language_version =~ /\d+/
      runtime_name != null
      runtime_version != null
      service_name != null
      tracer_version == '0.42.0'
    }
    with(body['host']) {
      hostname != null
      os != null
      os_version != null
    }
    body['request_type'] == 'APP_STARTED'
    body['runtime_id'] =~ /[\da-f]{8}-([\da-f]{4}-){3}[\da-f]{12}/
    body['seq_id'] > 0
    body['tracer_time'] > 0
    with(body['payload']) {
      request_type == 'APP_STARTED'
      with(configuration.first()) {
        name == 'name'
        value == 'value'
      }
      with(dependencies.first()) {
        hash == 'hash'
        name == 'name'
        type == 'SHAREDSYSTEMLIBRARY'
        version == '1.2.3'
      }
    }
  }
}
