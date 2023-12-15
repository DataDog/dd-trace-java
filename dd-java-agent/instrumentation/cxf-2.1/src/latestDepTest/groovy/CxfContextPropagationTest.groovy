import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import okhttp3.Request
import org.apache.cxf.endpoint.Server
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean
import org.apache.cxf.jaxrs.lifecycle.SingletonResourceProvider
import org.apache.cxf.transport.http_jetty.JettyHTTPDestination
import org.apache.cxf.transport.http_jetty.JettyHTTPServerEngine
import spock.lang.Shared

class CxfContextPropagationTest extends AgentTestRunner {

  @Shared
  Server server

  @Shared
  int port

  @Override
  void setupSpec() {
    JAXRSServerFactoryBean sf = new JAXRSServerFactoryBean()
    sf.setResourceClasses(TestResource)
    List<Object> providers = [new TestExceptionMapper()]
    sf.setProviders(providers)

    sf.setResourceProvider(TestResource,
      new SingletonResourceProvider(new TestResource(), true))
    sf.setAddress("http://localhost:0")

    server = sf.create()
    server.start()
    port = ((JettyHTTPServerEngine)((JettyHTTPDestination)server.getDestination()).getEngine()).getConnector().getLocalPort()
  }

  @Override
  void cleanupSpec() {
    server?.stop()
  }

  def "should propagate context on async request resume"() {
    setup:
    def client = OkHttpUtils.client()
    when:
    def response = client.newCall(new Request.Builder()
      .url("http://localhost:$port/test")
      .get().build()).execute()
    then:
    assert response.code() == 200
    assert response.body().string() == "Failure"

    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span {
          operationName "servlet.request"
          resourceName "GET /test"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "jakarta-rs"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.PEER_HOST_IPV4" "127.0.0.1"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" "http://localhost:$port/test"
            "$Tags.HTTP_HOSTNAME" "localhost"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_ROUTE" String
            "servlet.path" "/test"
            "$Tags.HTTP_USER_AGENT" String
            "$Tags.HTTP_CLIENT_IP" "127.0.0.1"
            defaultTags()
          }
        }
        span {
          operationName "jakarta-rs.request"
          resourceName "TestResource.someService"
          spanType DDSpanTypes.HTTP_SERVER
          errored true
          childOfPrevious()
          tags {
            "$Tags.COMPONENT" "jakarta-rs-controller"
            "error.message" { String }
            "error.type" { String }
            "error.stack" { String }
            defaultTags()
          }
        }
        TraceUtils.basicSpan(it, "trace.annotation", "TestResource.doSomething",span(1), null, ["component": "trace"] )
        TraceUtils.basicSpan(it, "trace.annotation", "TestExceptionMapper.toResponse",span(0), null, ["component": "trace"] )
      }
    }
  }
}
