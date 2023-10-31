package datadog.smoketest

import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REDACTION_ENABLED

abstract class AbstractGsonIastSpringBootSmokeTest extends  AbstractIastServerSmokeTest{


  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(getCustomSpringProperties())
    command.addAll([
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty(IAST_REDACTION_ENABLED, false)
    ])
    command.addAll((String[]) ['-jar', springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  abstract String[] getCustomSpringProperties()

  void 'request body taint json'() {
    setup:
    String url = "http://localhost:${httpPort}/request_body/test"
    def request = new Request.Builder().url(url).post(RequestBody.create(MediaType.parse('application/json; charset=utf-8'), '{"name": "nameTest", "value" : "valueTest"}')).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'nameTest' &&
        tainted.ranges[0].source.origin == 'http.request.body'
    }
  }

  void 'gson deserialization'() {

    given:
    final url = "http://localhost:${httpPort}/gson_deserialization"
    final body = new FormBody.Builder().add('json', '{"name": "gsonTest", "value" : "valueTest"}').build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasTainted { tainted ->
      tainted.value == 'gsonTest' &&
        tainted.ranges[0].source.name == 'json' &&
        tainted.ranges[0].source.origin == 'http.request.parameter'
    }
  }

  void 'gson json parser deserialization'() {

    given:
    final url = "http://localhost:${httpPort}/gson_json_parser_deserialization"
    final request = new Request.Builder().url(url).post(RequestBody.create(MediaType.parse('text/plain'), '{"name": "gsonReaderTest", "value" : "valueTest"}')).build()

    when:
    def response = client.newCall(request).execute()

    then:
    response.successful
    hasTainted { tainted ->
      tainted.value == 'gsonReaderTest' &&
        tainted.ranges[0].source.origin == 'http.request.body'
    }
  }
}
