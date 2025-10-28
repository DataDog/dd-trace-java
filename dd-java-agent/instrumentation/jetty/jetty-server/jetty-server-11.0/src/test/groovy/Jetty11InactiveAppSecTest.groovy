import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.instrumentation.servlet5.TestServlet5

class Jetty11InactiveAppSecTest extends AppSecInactiveHttpServerTest {
  HttpServer server() {
    new JettyServer(JettyServer.servletHandler(TestServlet5))
  }
}
