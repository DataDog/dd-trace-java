package datadog.trace.instrumentation.springsecurity5

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// Component scan defeats the purpose of configuring with specific classes
@SpringBootApplication(exclude = [ErrorMvcAutoConfiguration])
class AppConfig implements WebMvcConfigurer {

}
