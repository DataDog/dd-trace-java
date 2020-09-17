package test.boot

import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext

import static java.util.Collections.singletonMap

class CustomBeanClassloaderTest extends SpringBootBasedTest {
  @Override
  ConfigurableApplicationContext startServer(int port) {
    def app = new SpringApplication(AppConfig, SecurityConfig, AuthServerConfig, CustomClassloaderConfig)
    app.setDefaultProperties(singletonMap("server.port", port))
    def context = app.run()
    return context
  }
}
