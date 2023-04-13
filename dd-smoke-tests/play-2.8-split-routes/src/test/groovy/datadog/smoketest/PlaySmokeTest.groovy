package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Request
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.regex.Pattern

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static java.util.concurrent.TimeUnit.SECONDS

abstract class PlaySmokeTest extends AbstractServerSmokeTest {

  @Shared
  File playDirectory = new File("${buildDirectory}/stage/main")

  @Shared
  @AutoCleanup
  TestHttpServer clientServer = httpServer {
    handlers {
      prefix("/hello") {
        def parts = request.path.split("/")
        int id = parts.length == 0 ? 0 : Integer.parseInt(parts.last())
        String msg = "Hello ${id}!"
        if (id & 4) {
          Thread.sleep(100)
        }
        response.status(200).send(msg)
      }
    }
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    // If the server is not shut down correctly, this file can be left there and will block
    // the start of a new test
    def runningPid = new File(playDirectory.getPath(), "RUNNING_PID")
    if (runningPid.exists()) {
      runningPid.delete()
    }
    def command = isWindows() ? 'main.bat' : 'main'
    ProcessBuilder processBuilder =
      new ProcessBuilder("${playDirectory}/bin/${command}")
    processBuilder.directory(playDirectory)
    processBuilder.environment().put("JAVA_OPTS",
      defaultJavaProperties.join(" ")
      + " -Dconfig.file=${playDirectory}/conf/application.conf"
      + " -Dhttp.port=${httpPort}"
      + " -Dhttp.address=127.0.0.1"
      + " -Dplay.server.provider=${serverProvider()}"
      + " -Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()}:includeResource,DDAgentWriter"
      + " -Dclient.request.base=${clientServer.address}/")
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    new File("${buildDirectory}/tmp/trace-structure-play-2.8-${serverProviderName()}.out")
  }

  abstract String serverProviderName()

  abstract String serverProvider()

  @Override
  protected Set<String> expectedTraces() {
    return [
      "\\[(akka-http|netty)\\.request:GET /all\\[play\\.request:GET /all\\]\\]",
      "\\[(akka-http|netty)\\.request:GET /\\\$id\\<\\[\\^/\\]\\+\\>\\[play\\.request:GET /\\\$id\\<\\[\\^/\\]\\+\\>\\]\\]",
      "\\[(akka-http|netty)\\.request:GET /v1/post/all\\[play\\.request:GET /v1/post/all\\]\\]",
      "\\[(akka-http|netty)\\.request:GET /v1/post/\\\$id\\<\\[\\^/\\]\\+\\>\\[play\\.request:GET /v1/post/\\\$id\\<\\[\\^/\\]\\+\\>\\]\\]",
    ]
  }

  @Override
  protected Set<String> assertTraceCounts(Set<String> expected, Map<String, AtomicInteger> traceCounts) {
    List<Pattern> remaining = expected.collect { Pattern.compile(it) }.toList()
    for (def i = remaining.size() - 1; i >= 0; i--) {
      for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
        if (entry.getValue() > 0 && remaining.get(i).matcher(entry.getKey()).matches()) {
          remaining.remove(i)
          break
        }
      }
    }
    return remaining.collect { it.pattern() }.toSet()
  }

  def "get all at root"() {
    when:
    String url = "http://localhost:$httpPort/all"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "all"
    assert response.code() == 200

    waitForTraceCount(1) == 1
  }

  def "get by id at root"() {
    when:
    String url = "http://localhost:$httpPort/b53"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "Post #b53"
    assert response.code() == 200

    waitForTraceCount(1) == 1
  }

  def "get all at v1"() {
    when:
    String url = "http://localhost:$httpPort/v1/post/all"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "all"
    assert response.code() == 200

    waitForTraceCount(1) == 1
  }

  def "get by id at v1"() {
    when:
    String url = "http://localhost:$httpPort/v1/post/a33"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "Post #a33"
    assert response.code() == 200

    waitForTraceCount(1) == 1
  }

  // Ensure to clean up server and not only the shell script that starts it
  def cleanupSpec() {
    def pid = runningServerPid()
    if (pid) {
      def commands = isWindows() ? ['taskkill', '/PID', pid, '/T', '/F'] : ['kill', '-9', pid]
      new ProcessBuilder(commands).start().waitFor(10, SECONDS)
    }
  }

  def runningServerPid() {
    def runningPid = new File(playDirectory.getPath(), 'RUNNING_PID')
    if (runningPid.exists()) {
      return Files.lines(runningPid.toPath()).findAny().orElse(null)
    }
  }

  static isWindows() {
    return System.getProperty('os.name').toLowerCase().contains("win")
  }
}
