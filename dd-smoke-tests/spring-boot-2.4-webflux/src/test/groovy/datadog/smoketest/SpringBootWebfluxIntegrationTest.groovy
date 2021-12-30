package datadog.smoketest

import datadog.trace.test.agent.decoder.Decoder
import okhttp3.Request

class SpringBootWebfluxIntegrationTest extends AbstractServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter",
      // decoding received traces is only available for v05 right now
      "-Ddd.trace.agent.v0.5.enabled=true",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPort}"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile() {
    return File.createTempFile("trace-structure-docs", "out")
  }

  @Override
  protected Set<String> expectedTraces() {
    return ["[netty.request[WebController.hello]]"]
  }

  @Override
  Closure decodedTracesCallback() {
    // we don't want to do anything special with the decoded traces
    return {}
  }

  def "put docs and find all docs"() {
    setup:
    String url = "http://localhost:${httpPort}/hello"

    when:
    def response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("Hello world")
    waitForTraceCount(1)
    // now validate the traces we got back as well
    def trace = traces.get(0)
    def spans = Decoder.sortByStart(trace.spans)
    assert spans.size() == 2
    def nettySpan = spans.head()
    def controllerSpan = spans.tail().head()
    assert nettySpan.parentId == 0
    assert nettySpan.name == "netty.request"
    assert nettySpan.resource == "GET /hello"
    assert controllerSpan.parentId == nettySpan.spanId
    assert controllerSpan.name == "WebController.hello"
    assert controllerSpan.resource == "WebController.hello"
  }
}
