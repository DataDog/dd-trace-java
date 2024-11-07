package datadog.smoketest


import okhttp3.Request

import static datadog.trace.agent.test.utils.OkHttpUtils.clientBuilder
import static datadog.trace.agent.test.utils.OkHttpUtils.cookieJar

class SpringBootIastJpaIntegrationTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.bootWar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.iast.enabled=true",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  void 'test customer issue with one-to-many relation, add visitor: #forceVisitor'() {
    setup:

    final client = clientBuilder().cookieJar(cookieJar()).build()
    final createLib1 = client.newCall(request(forceVisitor).build()).execute()
    final createLib2 = client.newCall(request(forceVisitor).build()).execute()
    assert ![createLib1, createLib2].any { it.code() != 200 }
    final libraryId = createLib1.body().string().toInteger()

    when:
    // trigger
    final updateForm = client.newCall(request(forceVisitor, "/update/$libraryId").build()).execute()
    final libraryResult = client.newCall(request(forceVisitor, "/update").build()).execute()
    assert ![updateForm, libraryResult].any { it.code() != 200 }

    then:
    final hasIssue = libraryResult.body().string().toBoolean()
    hasIssue == forceVisitor

    where:
    forceVisitor << [false, true]
  }

  void 'validate that IAST does not trigger lazy relations, mode: #mode'() {
    setup:
    final client = clientBuilder().cookieJar(cookieJar()).build()
    final createLib = client.newCall(request().build()).execute()
    assert createLib.code() == 200
    final libraryId = createLib.body().string().toInteger()

    when:
    final test = client.newCall(request("/session/add/$libraryId?mode=$mode").build()).execute()
    final validate = client.newCall(request("/session/validate?mode=$mode").build()).execute()

    then:
    assert ![test, validate].any { it.code() != 200 }
    assert validate.body().string().toBoolean() == false : "Relation should not be triggered by IAST"

    where:
    mode << ['one-to-one', 'one-to-many']
  }

  private Request.Builder request(String suffix = '') {
    return new Request.Builder().url("http://localhost:${httpPort}/library$suffix").get()
  }

  private Request.Builder request(boolean forceVisitor, String suffix = '') {
    def builder = request(suffix)
    if (forceVisitor) {
      builder = builder.addHeader('X-Session-Visitor', 'true')
    }
    return builder
  }
}
