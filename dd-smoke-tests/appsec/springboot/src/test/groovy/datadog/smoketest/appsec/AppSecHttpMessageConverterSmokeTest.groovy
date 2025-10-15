package datadog.smoketest.appsec

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

import java.util.zip.GZIPInputStream

class AppSecHttpMessageConverterSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String customRulesPath = "${buildDirectory}/tmp/appsec_http_message_converter_rules.json"
    mergeRules(
      customRulesPath,
      [
        [
          id          : '__test_string_http_message_converter',
          name        : 'test rule for string http message converter',
          tags        : [
            type      : 'test',
            category  : 'test',
            confidence: '1',
          ],
          conditions  : [
            [
              parameters: [
                inputs: [[address: 'server.request.body']],
                regex : 'dd-test-http-message-converter',
              ],
              operator  : 'match_regex',
            ]
          ],
          transformers: [],
          on_match    : ['block']
        ]
      ])

    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    //    command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
    command.addAll(defaultJavaProperties)
    command.addAll(defaultAppSecProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-http-converter.out")
  }

  void 'test response schema extraction'() {
    given:
    def url = "http://localhost:${httpPort}/api_security/response"
    def body = [
      "main"    : [["key": "id001", "value": 1345.67], ["value": 1567.89, "key": "id002"]],
      "nullable": null,
    ]
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.get('application/json'), JsonOutput.toJson(body)))
      .build()

    when:
    final response = client.newCall(request).execute()
    waitForTraceCount(1)

    then:
    response.code() == 200
    def span = rootSpans.first()
    span.meta.containsKey('_dd.appsec.s.res.headers')
    span.meta.containsKey('_dd.appsec.s.res.body')
    final schema = new JsonSlurper().parse(unzip(span.meta.get('_dd.appsec.s.res.body')))
    assert schema == [["main": [[[["key": [8], "value": [16]]]], ["len": 2]], "nullable": [1]]]
  }

  void 'string http message converter raw body does not trigger parsed body rule'() {
    given:
    def url = "http://localhost:${httpPort}/api_security/request-body-string"
    def rawBody = '{"value":"dd-test-http-message-converter"}'
    def request = new Request.Builder()
      .url(url)
      .post(RequestBody.create(MediaType.get('application/json'), rawBody))
      .build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == rawBody

    when:
    waitForTraceCount(1)

    then:
    def spanWithTrigger = rootSpans.find { span ->
      (span.triggers ?: []).any { it['rule']['id'] == '__test_string_http_message_converter' }
    }
    assert spanWithTrigger == null
  }

  private static byte[] unzip(final String text) {
    final inflaterStream = new GZIPInputStream(new ByteArrayInputStream(text.decodeBase64()))
    return inflaterStream.getBytes()
  }
}
