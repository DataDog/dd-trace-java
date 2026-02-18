package test.boot

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.MatrixVariable
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.view.RedirectView

import javax.servlet.http.HttpServletRequest
import java.util.concurrent.CompletableFuture

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_JSON
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ENDPOINT_DISCOVERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.MATRIX_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_HERE
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK

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
  String matrix_param(@MatrixVariable(pathVar = 'var') MultiValueMap<String, String> v) {
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

  @PostMapping(value = "/body-multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseBody
  String body_multipart(@RequestParam MultiValueMap<String, String> body) {
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

  @RequestMapping(value = "/gimme-xml", produces = "text/xml")
  @ResponseBody
  String gimmeXml() {
    "<test/>"
  }

  @RequestMapping(value = "/gimme-html", produces = "text/html")
  @ResponseBody
  String gimmeHtml() {
    "\n" +
      "<!doctype html>\n" +
      "<html>\n" +
      "  <head>\n" +
      "    <title>This is the title of the webpage!</title>\n" +
      "  </head>\n" +
      "  <body>\n" +
      "    <p>This is an example paragraph. Anything in the <strong>body</strong> tag will appear on the page, just like this <strong>p</strong> tag and its contents.</p>\n" +
      "  </body>\n" +
      "</html>"
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
