package io.sqreen.testapp.sampleapp

import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(produces = 'text/plain')
class HealthController {

  @RequestMapping('/health')
  String health() {
    "OK\n"
  }
}
