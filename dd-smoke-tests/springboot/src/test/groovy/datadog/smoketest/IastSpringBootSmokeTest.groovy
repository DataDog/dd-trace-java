package datadog.smoketest

import datadog.trace.api.Platform
import datadog.trace.api.function.Function
import datadog.trace.test.agent.decoder.DecodedSpan
import okhttp3.Request
import spock.lang.IgnoreIf
import spock.util.concurrent.PollingConditions

@IgnoreIf({
  !Platform.isJavaVersionAtLeast(8)
})
class IastSpringBootSmokeTest extends AbstractServerSmokeTest {

  @Override
  def logLevel() {
    return "debug"
  }

  @Override
  Closure decodedTracesCallback() {
    return {} // force traces decoding
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(["-Ddd.iast.enabled=true", "-Ddd.iast.request-sampling=100"])
    command.addAll((String[]) ["-jar", springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    processBuilder
  }

  def "IAST subsystem starts"() {
    given: 'an initial request has succeeded'
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()
    client.newCall(request).execute()

    when: 'logs are read'
    String startMsg = null
    String errorMsg = null
    checkLog {
      if (it.contains("Not starting IAST subsystem")) {
        errorMsg = it
      }
      if (it.contains("IAST is starting")) {
        startMsg = it
      }
      // Check that there's no logged exception about missing classes from Datadog.
      // We had this problem before with JDK9StackWalker.
      if (it.contains("java.lang.ClassNotFoundException: datadog/")) {
        errorMsg = it
      }
    }

    then: 'there are no errors in the log and IAST has started'
    errorMsg == null
    startMsg != null
    !logHasErrors
  }

  def "default home page without errors"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Sup Dawg")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    checkLog()
    !logHasErrors
  }

  def "iast.enabled tag is present"() {
    setup:
    String url = "http://localhost:${httpPort}/greeting"
    def request = new Request.Builder().url(url).get().build()

    when:
    client.newCall(request).execute()

    then:
    waitForSpan(new PollingConditions(timeout: 5), hasMetric('_dd.iast.enabled', 1))
  }

  private static Function<DecodedSpan, Boolean> hasMetric(final String name, final Object value) {
    return { span -> value == span.metrics.get(name) }
  }
}
