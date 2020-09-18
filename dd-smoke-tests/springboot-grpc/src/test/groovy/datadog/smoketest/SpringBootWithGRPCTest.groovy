package datadog.smoketest

import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicInteger

abstract class SpringBootWithGRPCTest extends AbstractServerSmokeTest {

  @Shared
  File output = File.createTempFile("trace-structure-" + route(), "out")

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot-grpc.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) ["-Ddd.writer.type=TraceStructureWriter:${output.getAbsolutePath()}", "-jar", springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }


  def cleanupSpec() {
    // check the structures written out to the log,
    // and fail the run if anything unexpected was recorded
    verifyLog()
  }


  def verifyLog() {
    BufferedReader reader = new BufferedReader(new FileReader(output))
    Map<String, AtomicInteger> traceCounts = new HashMap<>()
    try {
      String line = reader.readLine()
      while (null != line) {
        traceCounts.computeIfAbsent(line, {
          new AtomicInteger()
        }).incrementAndGet()
        line = reader.readLine()
      }
    } finally {
      reader.close()
    }
    assert isAcceptable(traceCounts)
  }

  abstract boolean isAcceptable(Map<String, AtomicInteger> traceCounts)

  protected boolean assertTraceCounts(Set<String> expected, Map<String, AtomicInteger> traceCounts) {
    boolean ok = traceCounts.size() == expected.size()
    if (ok) {
      for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
        ok &= expected.contains(entry.getKey())
        ok &= entry.getValue().get() > 0
      }
    }
    return ok
  }

  abstract String route()

  def "greeter #n th time"() {
    setup:
    String url = "http://localhost:${httpPort}/${route()}"
    def request = new Request.Builder().url(url).get().build()

    when:
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr != null
    responseBodyStr.contains("bye")
    response.body().contentType().toString().contains("text/plain")
    response.code() == 200

    where:
    n << (1..200)
  }

}
