package datadog.telemetry

import datadog.telemetry.api.AppStarted
import datadog.telemetry.api.Dependency
import datadog.telemetry.api.DependencyType
import datadog.telemetry.api.GenerateMetrics
import datadog.telemetry.api.KeyValue
import datadog.telemetry.api.Metric
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

  void assertCommonHeaders(Request req) {
    assert req.header('Content-Type') == 'application/json; charset=utf-8'
    assert req.header('DD-Telemetry-API-Version') == 'v1'
    assert req.header('DD-Client-Library-Language') == 'jvm'
    assert !req.header('DD-Client-Library-Version').isEmpty()
    assert req.header('DD-Agent-Env') == null
    assert req.header('DD-Agent-Hostname') == null
  }

  void 'appStarted request'() {
    Request req
    def body

    when:
    AppStarted payload = new AppStarted(
      requestType: RequestType.APP_STARTED,
      configuration: [new KeyValue(name: 'name', value: 'value')],
      dependencies: [
        new Dependency(
        hash: 'hash', name: 'name', type: DependencyType.SHARED_SYSTEM_LIBRARY, version: '1.2.3')
      ]
      )
    req = reqBuilder.build(RequestType.APP_STARTED, payload)
    body = parseBody req.body()

    then:
    assertCommonHeaders(req)
    req.header('DD-Telemetry-Request-Type') == 'app-started'
    body['api_version'] == 'v1'
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
      kernel_name != null
      kernel_release != null
      kernel_version != null
    }
    body['request_type'] == 'app-started'
    body['runtime_id'] =~ /[\da-f]{8}-([\da-f]{4}-){3}[\da-f]{12}/
    body['seq_id'] > 0
    body['tracer_time'] > 0
    with(body['payload']) {
      request_type == 'app-started'
      with(configuration.first()) {
        name == 'name'
        value == 'value'
      }
      with(dependencies.first()) {
        hash == 'hash'
        name == 'name'
        type == 'SharedSystemLibrary'
        version == '1.2.3'
      }
    }
  }

  void 'metrics can be serialized'() {
    GenerateMetrics payload = new GenerateMetrics(
      series: [
        new Metric(
        common: false,
        type: Metric.TypeEnum.GAUGE,
        metric: 'test',
        tags: ['example_tag'],
        points: [[1660307486, 224]]
        )
      ]
      )

    when:
    Request req = reqBuilder.build(RequestType.GENERATE_METRICS, payload)
    def body = parseBody req.body()

    then:
    assertCommonHeaders(req)
    req.header('DD-Telemetry-Request-Type') == 'generate-metrics'
    body['api_version'] == 'v1'
    body['request_type'] == 'generate-metrics'
    with(body['payload']) {
      series == [
        [
          common: false,
          metric: 'test',
          type: 'gauge',
          tags: ['example_tag'],
          points: [[1660307486, 224]]
        ]
      ]
    }
  }
}
