import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.utils.OkHttpUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.config.TracerConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.servlet5.TestServlet5
import okhttp3.Request
import org.apache.catalina.Context
import org.apache.catalina.Wrapper
import spock.lang.Shared

class TomcatNoPropagationForkedTest extends InstrumentationSpecification {
  @Shared
  TomcatServer server = new TomcatServer("/", false, { Context ctx ->
    Wrapper wrapper = ctx.createWrapper()
    wrapper.name = UUID.randomUUID()
    wrapper.servletClass = TestServlet5.name
    wrapper.asyncSupported = true
    ctx.addChild(wrapper)
    ctx.addServletMappingDecoded("/*", wrapper.name)
  }, {})

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TracerConfig.PROPAGATION_STYLE_EXTRACT, "none")
  }

  @Override
  void setupSpec() {
    server.start()
  }

  @Override
  void cleanupSpec() {
    server.stop()
  }


  def "should not extract propagated context but collect headers"() {
    setup:
    def client = OkHttpUtils.client()
    def request = new Request.Builder()
      .url(server.address().resolve(SUCCESS.relativePath()).toURL())
      .get()
      .header("X-Forwarded-For", "1.2.3.4")
      .build()
    def response = runUnderTrace("parent", {
      client.newCall(request).execute()
    })

    expect:
    response.code() == SUCCESS.status
    response.body().string() == SUCCESS.body

    and:
    assertTraces(2) {
      trace(1) {
        span {
          operationName "parent"
        }
      }
      trace(2) {
        span {
          operationName "servlet.request"
          spanType DDSpanTypes.HTTP_SERVER
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "tomcat-server"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
            "$Tags.HTTP_CLIENT_IP" { it != null }
            "$Tags.PEER_HOST_IPV4" { it != null }
            "$Tags.PEER_PORT" { it != null }
            "$Tags.HTTP_HOSTNAME" { it != null }
            "$Tags.HTTP_URL" { it != null }
            "servlet.context" "/"
            "$Tags.HTTP_METHOD" { it != null }
            "$Tags.HTTP_STATUS" 200
            "$Tags.HTTP_USER_AGENT" { it != null }
            "$Tags.HTTP_FORWARDED_IP" "1.2.3.4"
            defaultTags(false)
          }
        }
        span {
          operationName "controller"
          childOfPrevious()
        }
      }
    }
  }
}
