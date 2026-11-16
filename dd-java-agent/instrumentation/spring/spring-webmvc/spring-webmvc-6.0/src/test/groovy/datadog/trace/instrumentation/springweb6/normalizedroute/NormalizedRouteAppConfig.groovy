package datadog.trace.instrumentation.springweb6.normalizedroute

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Route shapes that need presence logic, on the Boot 3 default PathPatternParser. */
@SpringBootApplication(exclude = [SecurityAutoConfiguration, SecurityFilterAutoConfiguration, UserDetailsServiceAutoConfiguration])
class NormalizedRouteAppConfig {
}

@RestController
class NormalizedRouteController {

  @GetMapping('/regex/{id:\\d+}')
  String regex() {
    'ok'
  }

  @GetMapping('/nested/{code:[0-9]{3}}')
  String nestedRegex() {
    'ok'
  }

  @GetMapping('/files/*.txt')
  String singleStar() {
    'ok'
  }

  @GetMapping('/static/**')
  String trailingDoubleStar() {
    'ok'
  }

  @GetMapping('/mixed/{id}/**')
  String namedAndDoubleStar() {
    'ok'
  }

  @GetMapping('/star/*/**')
  String starAndDoubleStar() {
    'ok'
  }

  @GetMapping('/rest/{*rest}')
  String captureRest() {
    'ok'
  }
}
