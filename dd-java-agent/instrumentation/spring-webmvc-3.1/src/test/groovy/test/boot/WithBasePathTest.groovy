package test.boot

import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

class WithBasePathTest extends SpringBootBasedTest {
  @Configuration
  @PropertySource("classpath:with-base-path-test.properties")
  static class Config {
  }

  @Override
  SpringApplication application() {
    return new SpringApplication(Config, AppConfig, SecurityConfig, AuthServerConfig, TestController)
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(ServerEndpoint endpoint) {
    ["servlet.path": "/spring-servlet", "servlet.context": "/$servletContext"] +
    extraServerTags
  }
}
