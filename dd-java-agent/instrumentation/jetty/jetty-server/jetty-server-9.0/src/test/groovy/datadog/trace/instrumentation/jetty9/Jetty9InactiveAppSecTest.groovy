package datadog.trace.instrumentation.jetty9

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import test.JettyServer
import test.TestHandler

import static org.junit.jupiter.api.Assumptions.assumeTrue

class Jetty9InactiveAppSecTest extends AppSecInactiveHttpServerTest {
  HttpServer server() {
    new JettyServer(TestHandler.INSTANCE)
  }

  @Override
  void multipart() {
    // jetty-appsec-8.1.3 covers [8.1.3, 9.2.0.RC0) which includes Jetty 9.0.x.
    // It instruments extractContentParameters() but calls ParameterCollector.put(String, String)
    // which does not exist in Jetty 9.0.x → HTTP 500 on multipart requests.
    assumeTrue(false, 'multipart not supported on Jetty 9.0.x due to jetty-appsec-8.1.3 range conflict')
  }
}
