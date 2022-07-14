package datadog.smoketest

import okhttp3.Request
import okhttp3.Response
import spock.lang.Requires
import spock.lang.Shared

@Requires({
  !System.getProperty("java.vm.name").contains("IBM J9 VM")
})
class SpringBootOpenLibertySnapshotTest extends AbstractTestAgentSmokeTest {

  @Shared
  String openLibertyShadowJar = System.getProperty("datadog.smoketest.openliberty.jar.path")

  @Override
  ProcessBuilder createProcessBuilder() {
    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.jmxfetch.enabled=false",
      "-jar",
      openLibertyShadowJar,
      "--server.port=${httpPort}"
    ])

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  def "Test trace snapshot of sending single request to Openliberty server"() {
    setup:
    Response response
    String[] ignoredKeys =  [
      'meta.http.url',
      'meta.thread.name',
      'metrics.peer.port',
      'metrics.thread.id',
      'meta.servlet.path',
      'meta.http.useragent'
    ]
    snapshot("datadog.smoketest.SpringBootOpenLibertySnapshotTest.simple", ignoredKeys,{
      def url = "http://localhost:${httpPort}/connect/0"
      def request = new Request.Builder().url(url).get().build()
      response = client.newCall(request).execute()
    })

    expect:
    response != null
    response.code() == 200
  }

  def "Test trace snapshot of sending nested request to Openliberty server"() {
    setup:
    Response response
    String[] ignoredKeys =  [
      'meta.http.url',
      'meta.thread.name',
      'metrics.peer.port',
      'metrics.thread.id',
      'meta.servlet.path',
      'meta.http.useragent'
    ]
    snapshot("datadog.smoketest.SpringBootOpenLibertySnapshotTest.nested", ignoredKeys, {
      def url = "http://localhost:${httpPort}/connect"
      def request = new Request.Builder().url(url).get().build()
      response = client.newCall(request).execute()
    })

    expect:
    response != null
    response.code() == 200
  }

  /*
   * TODO this test does not actually follow the intent that I believe we are looking for.
   *  I believe the intent is that we use the liberty exception->status_code mapping
   *  configuration to map an exception to 404.
   */
  def "Test trace snapshot for server exception" () {
    setup:
    Response response
    String[] ignoredKeys =  [
      'meta.http.url',
      'meta.thread.name',
      'metrics.peer.port',
      'metrics.thread.id',
      'meta.error.stack',
      'meta.http.useragent'
    ]
    snapshot("datadog.smoketest.SpringBootOpenLibertySnapshotTest.exception404", ignoredKeys, {
      def url = "http://localhost:${httpPort}/randomEndpoint"
      def request = new Request.Builder().url(url).get().build()
      response = client.newCall(request).execute()
    })

    expect:
    response != null
    response.code() == 500
  }
}
