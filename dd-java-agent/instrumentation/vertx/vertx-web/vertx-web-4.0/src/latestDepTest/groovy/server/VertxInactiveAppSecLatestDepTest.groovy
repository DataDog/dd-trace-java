package server

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer

class VertxInactiveAppSecLatestDepTest extends AppSecInactiveHttpServerTest {
  @Override
  boolean isTestPathParam() {
    true
  }

  HttpServer server() {
    new VertxServer(VertxTestServer, '/')
  }
}
