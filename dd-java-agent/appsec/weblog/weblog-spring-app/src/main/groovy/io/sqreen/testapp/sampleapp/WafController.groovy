package io.sqreen.testapp.sampleapp

import org.springframework.http.HttpEntity

import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class WafController {

  @RequestMapping(value=['/waf', '/waf/**'], consumes = 'application/json')
  @ResponseBody
  String waf_json(@RequestBody Object payload) {
    "json: " + payload
  }

  @RequestMapping(value=['/waf', '/waf/**'], consumes = 'text/plain')
  @ResponseBody
  String waf_text(@RequestBody String payload) {
    "text: " + payload
  }

  @RequestMapping(value=['/waf', '/waf/**'])
  @ResponseBody
  String waf_other(HttpEntity<String> httpEntity) {
    httpEntity.getBody()
  }
}
