

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer

class GrizzlyInactiveAppSecTest extends AppSecInactiveHttpServerTest {
  @Override
  boolean isTestPathParam() {
    true
  }

  HttpServer server() {
    new GrizzlyServer(ServiceResource)
  }
}
