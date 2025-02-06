package test.boot

import org.springframework.boot.SpringApplication

class CustomBeanClassloaderTest extends SpringBootBasedTest {

  @Override
  SpringApplication application() {
    return new SpringApplication(AppConfig, SecurityConfig, AuthServerConfig, CustomClassloaderConfig, TestController, WebsocketConfig)
  }
}
