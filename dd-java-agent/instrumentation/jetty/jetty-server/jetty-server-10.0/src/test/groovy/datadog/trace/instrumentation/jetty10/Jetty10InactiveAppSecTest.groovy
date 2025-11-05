package datadog.trace.instrumentation.jetty10

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import test.JettyServer
import test.TestHandler

class Jetty10InactiveAppSecTest extends AppSecInactiveHttpServerTest {
  HttpServer server() {
    new JettyServer(TestHandler.INSTANCE)
  }
}
