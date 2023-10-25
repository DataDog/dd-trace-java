package datadog.trace.instrumentation.jetty9

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer

class Jetty9InactiveAppSecTest extends AppSecInactiveHttpServerTest {
  HttpServer server() {
    new JettyServer(TestHandler.INSTANCE)
  }
}
