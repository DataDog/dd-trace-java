package datadog.smoketest

import okhttp3.Request
import okhttp3.Response
import spock.lang.Requires
import spock.lang.Shared

@Requires({
  !System.getProperty("java.vm.name").contains("IBM J9 VM") && System.getenv("CI") != "true"
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
    snapshot("datadog.smoketest.SpringBootOpenLibertySnapshotTest.simple", {
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
    String[] ignoredKeys =  ['meta.http.url', 'meta.thread.name', 'meta.peer.port', 'meta.thread.id', "meta.servlet.path"]
    snapshot("datadog.smoketest.SpringBootOpenLibertySnapshotTest.nested", ignoredKeys, {
      def url = "http://localhost:${httpPort}/connect"
      def request = new Request.Builder().url(url).get().build()
      response = client.newCall(request).execute()
    })

    expect:
    response != null
    response.code() == 200
  }

  def "Test trace snapshot for server exception" () {
    setup:
    Response response
    snapshot("datadog.smoketest.SpringBootOpenLibertySnapshotTest.exception404", {
      def url = "http://localhost:${httpPort}/randomEndpoint"
      def request = new Request.Builder().url(url).get().build()
      response = client.newCall(request).execute()
    })

    expect:
    response != null
    response.code() != 200
  }
}
