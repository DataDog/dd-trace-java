package test.dynamic

import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.view.RedirectView

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class TestController {

  @ResponseBody
  String success() {
    HttpServerTest.controller(SUCCESS) {
      SUCCESS.body
    }
  }


  @ResponseBody
  String query(@RequestParam("some") String param) {
    HttpServerTest.controller(QUERY_PARAM) {
      "some=$param"
    }
  }


  @ResponseBody
  String path(@PathVariable Integer id) {
    HttpServerTest.controller(PATH_PARAM) {
      "$id"
    }
  }


  @ResponseBody
  RedirectView redirect() {
    HttpServerTest.controller(REDIRECT) {
      new RedirectView(REDIRECT.body)
    }
  }

  ResponseEntity error() {
    HttpServerTest.controller(ERROR) {
      new ResponseEntity(ERROR.body, HttpStatus.valueOf(ERROR.status))
    }
  }

  ResponseEntity exception() {
    HttpServerTest.controller(EXCEPTION) {
      throw new Exception(EXCEPTION.body)
    }
  }
}
