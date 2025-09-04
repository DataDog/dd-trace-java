package server

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.server.http.HttpProxy
import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.internal.http.HttpMethod
import spock.lang.AutoCleanup
import spock.lang.Requires
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

/* Don't actually need AgentTestRunner, but it messes up the classloader for AgentTestRunnerTest if this runs first. */

@Requires({
  !System.getProperty("java.vm.name").contains("IBM J9 VM")
})
class HttpProxyTest extends InstrumentationSpecification {

  @AutoCleanup
  @Shared
  def proxy = new HttpProxy()

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      get("/get") {
        response.send("/get response")
      }
      post("/post") {
        response.send("/post response")
      }
      put("/put") {
        response.send("/put response")
      }
    }
  }

  @Shared
  OkHttpClient client = OkHttpUtils.client(server, new ProxySelector() {
    @Override
    List<Proxy> select(URI uri) {
      Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", proxy.port)))
    }

    @Override
    void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
      getDefault().connectFailed(uri, sa, ioe)
    }
  })


  def request(String path) {
    return new Request.Builder().url(server.secureAddress.resolve(path).toURL())
  }

  def "test proxy with #method call"() {
    setup:
    proxy.requestCount.set(0)
    def req = request("/$method").method(method, reqBody).build()
    def call = client.newCall(req)

    when:
    def response = call.execute()

    then:
    response.body().string() == "/$method response"
    response.code() == 200
    proxy.requestCount() == expectedCount

    where:
    method | body        | expectedCount
    "get"  | null        | 1
    "post" | null        | 0 // same request as above from proxy perspective due to keep-alive
    "post" | "some body" | 0 // same request as above from proxy perspective due to keep-alive
    "put"  | "some body" | 0 // same request as above from proxy perspective due to keep-alive

    reqBody = HttpMethod.requiresRequestBody(method) ? RequestBody.create(MediaType.parse("text/plain"), body) : null
  }
}
