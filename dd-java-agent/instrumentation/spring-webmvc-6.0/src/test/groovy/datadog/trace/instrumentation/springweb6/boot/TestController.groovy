package datadog.trace.instrumentation.springweb6.boot

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.view.RedirectView

import jakarta.servlet.http.HttpServletRequest

import java.util.concurrent.CompletableFuture

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.*

@Controller
class TestController {

  @RequestMapping("/success")
  @ResponseBody
  String success() {
    HttpServerTest.controller(SUCCESS) {
      SUCCESS.body
    }
  }

  @RequestMapping("/forwarded")
  @ResponseBody
  CompletableFuture<String> forwarded(HttpServletRequest request) {
    CompletableFuture.supplyAsync {
      HttpServerTest.controller(FORWARDED) {
        request.getHeader("x-forwarded-for")
      }
    }
  }

  @RequestMapping("/query")
  @ResponseBody
  String query_param(@RequestParam("some") String param) {
    HttpServerTest.controller(QUERY_PARAM) {
      "some=$param"
    }
  }

  @RequestMapping("/encoded_query")
  @ResponseBody
  String query_encoded_query(@RequestParam("some") String param) {
    HttpServerTest.controller(QUERY_ENCODED_QUERY) {
      "some=$param"
    }
  }

  @RequestMapping("/encoded path query")
  @ResponseBody
  String query_encoded_both(@RequestParam("some") String param) {
    HttpServerTest.controller(QUERY_ENCODED_BOTH) {
      "some=$param"
    }
  }

  @RequestMapping("/path/{id}/param")
  @ResponseBody
  String path_param(@PathVariable Integer id) {
    HttpServerTest.controller(PATH_PARAM) {
      "$id"
    }
  }

  @RequestMapping("/matrix/{var}")
  @ResponseBody
  String matrix_param(@MatrixVariable(pathVar = 'var') MultiValueMap<String, String> v, HttpServletRequest request) {
    HttpServerTest.controller(MATRIX_PARAM) {
      v as String
    }
  }

  @RequestMapping("/user-block")
  String userBlock() {
    HttpServerTest.controller(USER_BLOCK) {
      Blocking.forUser('user-to-block').blockIfMatch()
      'should not be reached'
    }
  }

  @RequestMapping(value = "/body-urlencoded",
  method = RequestMethod.POST, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
  @ResponseBody
  String body_urlencoded(@RequestParam MultiValueMap<String, String> body) {
    HttpServerTest.controller(BODY_URLENCODED) {
      body.findAll { it.key != 'ignore' } as String
    }
  }

  @PostMapping(value = "/body-json",
  consumes = MediaType.APPLICATION_JSON_VALUE,
  produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  Map<String, Object> body_json(@RequestBody Map<String, Object> body) {
    HttpServerTest.controller(BODY_JSON) {
      body
    }
  }

  @RequestMapping("/redirect")
  @ResponseBody
  RedirectView redirect() {
    HttpServerTest.controller(REDIRECT) {
      new RedirectView(REDIRECT.body)
    }
  }

  @RequestMapping("/not-here")
  ResponseEntity not_here() {
    HttpServerTest.controller(NOT_HERE) {
      new ResponseEntity(NOT_HERE.body, HttpStatus.valueOf(NOT_HERE.status))
    }
  }

  @RequestMapping("/error-status")
  ResponseEntity error() {
    HttpServerTest.controller(ERROR) {
      new ResponseEntity(ERROR.body, HttpStatus.valueOf(ERROR.status))
    }
  }

  @RequestMapping("/exception")
  ResponseEntity exception() {
    HttpServerTest.controller(EXCEPTION) {
      throw new Exception(EXCEPTION.body)
    }
  }

  @RequestMapping("/secure/success")
  @ResponseBody
  String secure_success() {
    HttpServerTest.controller(SECURE_SUCCESS) {
      SECURE_SUCCESS.body
    }
  }

  @RequestMapping(value = "/discovery",
  method = [RequestMethod.POST, RequestMethod.PATCH, RequestMethod.PUT],
  consumes = MediaType.APPLICATION_JSON_VALUE,
  produces = MediaType.TEXT_PLAIN_VALUE)
  ResponseEntity discovery() {
    HttpServerTest.controller(ENDPOINT_DISCOVERY) {
      new ResponseEntity(ENDPOINT_DISCOVERY.body, HttpStatus.valueOf(ENDPOINT_DISCOVERY.status))
    }
  }

  @ExceptionHandler
  ResponseEntity handleException(Throwable throwable) {
    new ResponseEntity(throwable.message, HttpStatus.INTERNAL_SERVER_ERROR)
  }
}
