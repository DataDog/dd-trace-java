import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.instrumentation.servlet5.TestServlet5
import org.apache.catalina.Context
import org.apache.catalina.Wrapper

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN

class TomcatAppSecInactiveHttpServerForkedTest extends AppSecInactiveHttpServerTest {
  HttpServer server() {
    new TomcatServer('tomcat-context', false, { Context ctx ->
      HttpServerTest.ServerEndpoint.values().findAll {
        it != NOT_FOUND && it != UNKNOWN
      }.each {
        Wrapper wrapper = ctx.createWrapper()
        wrapper.name = UUID.randomUUID()
        wrapper.servletClass = TestServlet5.name
        wrapper.asyncSupported = true
        ctx.addChild(wrapper)
        ctx.addServletMappingDecoded(it.path, wrapper.name)
      }
    }, {})
  }
}
