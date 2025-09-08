package test.filter

import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.view.RedirectView

import javax.servlet.http.HttpServletRequest

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

/**
 * None of the methods in this controller should be called because they are intercepted
 * by the filter
 */
@Controller
class TestController {

  @RequestMapping("/success")
  @ResponseBody
  String success() {
    HttpServerTest.controller(SUCCESS) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/forwarded")
  @ResponseBody
  String forwarded(HttpServletRequest request) {
    HttpServerTest.controller(FORWARDED) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/query")
  @ResponseBody
  String query_param(@RequestParam("some") String param) {
    HttpServerTest.controller(QUERY_PARAM) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/encoded_query")
  @ResponseBody
  String query_encoded_query(@RequestParam("some") String param) {
    HttpServerTest.controller(QUERY_ENCODED_QUERY) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/encoded path query")
  @ResponseBody
  String query_encoded_both(@RequestParam("some") String param) {
    HttpServerTest.controller(QUERY_ENCODED_BOTH) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/path/{id}/param")
  @ResponseBody
  String path_param(@PathVariable Integer id) {
    HttpServerTest.controller(PATH_PARAM) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/redirect")
  @ResponseBody
  RedirectView redirect() {
    HttpServerTest.controller(REDIRECT) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/error-status")
  ResponseEntity error() {
    HttpServerTest.controller(ERROR) {
      throw new Exception("This should not be called")
    }
  }

  @RequestMapping("/exception")
  ResponseEntity exception() {
    HttpServerTest.controller(EXCEPTION) {
      throw new Exception("This should not be called")
    }
  }

  @ExceptionHandler
  ResponseEntity handleException(Throwable throwable) {
    new ResponseEntity(throwable.message, HttpStatus.INTERNAL_SERVER_ERROR)
  }
}
