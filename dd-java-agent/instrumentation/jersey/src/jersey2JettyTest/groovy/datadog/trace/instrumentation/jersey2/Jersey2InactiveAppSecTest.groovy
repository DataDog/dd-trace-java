package datadog.trace.instrumentation.jersey2

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer

class Jersey2InactiveAppSecTest extends AppSecInactiveHttpServerTest {
  @Override
  boolean isTestPathParam() {
    true
  }

  HttpServer server() {
    new JettyServer()
  }
}
