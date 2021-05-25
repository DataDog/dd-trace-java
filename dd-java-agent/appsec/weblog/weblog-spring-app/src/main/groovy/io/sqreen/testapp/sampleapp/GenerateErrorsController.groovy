package io.sqreen.testapp.sampleapp

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

@Controller
class GenerateErrorsController {
  @RequestMapping('/internal_error/')
  String raiseException() {
    throw new RuntimeException('exception raised on request')
  }
}
