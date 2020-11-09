import datadog.trace.agent.test.base.WithHttpServer
import datadog.trace.agent.test.utils.ThreadUtils
import okhttp3.Request
import spock.lang.Shared

import java.util.concurrent.atomic.AtomicInteger

class AkkaHttpServerConcurrentTest extends WithHttpServer<ConcurrentTestWebServer> {

  @Override
  ConcurrentTestWebServer startServer(int port) {
    return ConcurrentTestWebServer.start(port)
  }

  @Override
  void stopServer(ConcurrentTestWebServer webServer) {
    webServer.stop()
  }

  @Shared
  def totalInvocations = 200

  @Shared
  AtomicInteger counter = new AtomicInteger(0)

  void doAndValidateRequest(int id) {
    def type = id & 1 ? "p" : "f"
    String url = address.toString() + "akka-http/${type}ing/$id"
    def traceId = totalInvocations + id
    def request = new Request.Builder().url(url).get().header("x-datadog-trace-id", traceId.toString()).build()
    def response = client.newCall(request).execute()
    def responseBodyStr = response.body().string()
    assert responseBodyStr == "${type}ong $id -> $traceId"
    assert response.code() == 200
  }

  def "propagate trace id when we ping akka-http concurrently"() {
    expect:
    ThreadUtils.runConcurrently(10, totalInvocations, {
      def id = counter.incrementAndGet()
      doAndValidateRequest(id)
    })

    and:
    TEST_WRITER.waitForTraces(totalInvocations)
  }
}
