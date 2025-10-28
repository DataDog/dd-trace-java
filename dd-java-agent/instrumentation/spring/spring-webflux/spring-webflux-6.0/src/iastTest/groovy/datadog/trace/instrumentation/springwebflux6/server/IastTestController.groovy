package datadog.trace.instrumentation.springwebflux6.server

import groovy.transform.Canonical
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.MatrixVariable
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

import static com.datadog.iast.test.TaintMarkerHelpers.t

@RestController
class IastTestController {

  @GetMapping("/iast/path/{var1}")
  Mono<String> getIastPath(@PathVariable String var1) {
    Mono.just("IAST: ${t(var1)}")
  }

  @GetMapping('/iast/matrix/{var1}')
  Mono<String> getIastMatrix(@MatrixVariable(pathVar = 'var1') MultiValueMap<String, String> var1) {
    Mono.just("IAST: ${t(var1)}")
  }

  @GetMapping('/iast/query')
  Mono<String> getIastQuery(@RequestParam('var1') String var1) {
    Mono.just("IAST: ${t(var1)}")
  }

  @GetMapping('/iast/header')
  Mono<String> getIastHeader(@RequestHeader('x-my-header') String var1) {
    Mono.just("IAST: ${t(var1)}")
  }

  @GetMapping('/iast/all_headers')
  Mono<String> getIastHeader(@RequestHeader Map<String, String> headers) {
    def m = headers.collectEntries {
      [t(it.key), t(it.value)]
    }
    Mono.just("IAST: $m")
  }

  @SuppressWarnings("MethodName")
  private static _t(MultiValueMap mvm) {
    mvm.collectEntries { e ->
      [t(e.key), e.value.collect {t(it) }]
    }
  }

  @SuppressWarnings("MethodName")
  private static _tc(MultiValueMap<String, HttpCookie> mvm) {
    mvm.collectEntries { e ->
      [t(e.key), e.value.collect {c -> "${t(c.name)} -> ${t(c.value)}" }]
    }
  }

  @GetMapping('/iast/all_headers_mvm')
  Mono<String> getIastHeader(@RequestHeader MultiValueMap<String, String> headers) {
    Mono.just("IAST: ${_t(headers)}")
  }

  @GetMapping('/iast/cookie')
  Mono<String> getIastCookie(@CookieValue('var1') String var1) {
    Mono.just("IAST: ${t(var1)}")
  }

  @GetMapping('/iast/shr')
  Mono<String> getIastShr(ServerHttpRequest shr) {
    Mono.just(
      """
      URI: ${shr.URI}
      Path: ${shr.path.pathWithinApplication().value()}
      Query params: ${_t(shr.queryParams)}
      Cookies: ${_tc(shr.cookies)}
      User-Agent: ${t(shr.headers.getFirst('user-agent'))}
      """.stripIndent())
  }

  @Canonical
  static class MyJsonObject {
    String var1
    List<String> var2

    @Override
    String toString() {
      return "MyJsonObject{" +
        "var1='" + t(var1) + '\'' +
        ", var2=" + var2.collect {t(it) } +
        '}'
    }
  }

  @PostMapping(value = '/iast/json', consumes = ['application/json'])
  Mono<String> postIastJson(@RequestBody MyJsonObject obj) {
    Mono.just("IAST: $obj")
  }
}
