package datadog.smoketest

import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

class PlayNettySmokeTest extends AbstractServerSmokeTest {

  @Shared
  File playDirectory = new File("${buildDirectory}/stage/playBinary")

  @Override
  ProcessBuilder createProcessBuilder() {
    ProcessBuilder processBuilder =
      new ProcessBuilder("${playDirectory}/bin/playBinary")
    processBuilder.directory(playDirectory)
    processBuilder.environment().put("JAVA_OPTS",
      defaultJavaProperties.join(" ")
        + " -Dconfig.file=${playDirectory}/conf/application.conf"
        + " -Dhttp.port=${httpPort}"
        + " -Dhttp.address=127.0.0.1"
        + " -Dplay.server.provider=play.core.server.NettyServerProvider"
        + " -Ddd.writer.type=TraceStructureWriter:${output.getAbsolutePath()}")
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    return new File("${buildDirectory}/tmp/trace-structure-play-2.5-netty.out")
  }

  @Shared
  int totalInvocations = 100

  @Override
  protected boolean isAcceptable(Map<String, AtomicInteger> traceCounts) {
    // Since the filters ([filter2-4]) are executed after each other but potentially on different threads, and the future
    // that is completed is completed before the span is finished, the order of those filters and the request processing
    // is undefined.
    boolean isOk = true
    def allowed = ~/\[netty.request\[filter1(\[filter\d\])?(\[filter\d\])?(\[filter\d\])?\[play.request\[action1\[action2\]\]\](\[filter\d\])?(\[filter\d\])?(\[filter\d\])?\]\]/
    traceCounts.entrySet().each {
      def matches = (it.key =~ allowed).findAll().head().findAll{ it != null }
      isOk &= matches.size() == 4
      isOk &= matches.contains("[filter2]")
      isOk &= matches.contains("[filter3]")
      isOk &= matches.contains("[filter4]")
    }
    // So we can't count the number of traces written since we don't properly flush the PendingTrace when
    // the test server is shut down
    return traceCounts.size() > 0 && isOk
  }

  void doAndValidateRequest(int id) {
    String url = "http://localhost:$httpPort/welcome?id=$id"
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "Welcome $id."
    assert response.code() == 200
  }

  def "get welcome endpoint in parallel with filters and action"() {
    expect:
    // Do one request before to initialize the server
    doAndValidateRequest(1)
    ThreadUtils.runConcurrently(10, totalInvocations - 1, {
      def id = ThreadLocalRandom.current().nextInt(1, 4711)
      doAndValidateRequest(id)
    })
  }
}
