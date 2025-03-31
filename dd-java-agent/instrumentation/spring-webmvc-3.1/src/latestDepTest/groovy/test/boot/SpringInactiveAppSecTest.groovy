package test.boot

import com.datadog.appsec.AppSecInactiveHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import org.springframework.boot.SpringApplication

class SpringInactiveAppSecTest extends AppSecInactiveHttpServerTest {
  @Override
  boolean isTestPathParam() {
    true
  }

  @Override
  boolean isTestMatrixParam() {
    true
  }

  HttpServer server() {
    new SpringBootServer(
      new SpringApplication(AppConfig, SecurityConfig, AuthServerConfig, TestController),
      'boot-context'
      )
  }
}
