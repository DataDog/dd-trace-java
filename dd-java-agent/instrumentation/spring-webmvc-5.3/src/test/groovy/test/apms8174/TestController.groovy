package test.apms8174

import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM

@Controller
class TestController {

  static final String PATH_PATTERN = "/path/{id}/param"

  @RequestMapping(PATH_PATTERN)
  @ResponseBody
  String path_param(@PathVariable Integer id) {
    HttpServerTest.controller(PATH_PARAM) {
      "$id"
    }
  }
}
