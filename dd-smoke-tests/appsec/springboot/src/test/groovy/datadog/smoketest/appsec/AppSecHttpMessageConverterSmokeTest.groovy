package datadog.smoketest.appsec

import groovy.json.JsonOutput
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

class AppSecHttpMessageConverterSmokeTest extends AbstractAppSecServerSmokeTest {

  @Override
  def logLevel() {
    'DEBUG'
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.appsec.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
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
      source: 'AppSecSpringSmokeTest',
      tests : [
        [
          name  : 'API Security samples only one request per endpoint',
          status: 'SUCCESS'
        ],
        [
          name  : 'test response schema extraction',
          status: 'FAILED'
        ]
      ]
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
  }
}
