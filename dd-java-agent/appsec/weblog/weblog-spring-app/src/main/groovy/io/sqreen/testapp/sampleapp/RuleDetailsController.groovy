package io.sqreen.testapp.sampleapp

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = '/rule', produces = 'application/json')
class RuleDetailsController {

  @Autowired
  AppSecInfo sqreenInfo

  @RequestMapping
  String index(@RequestParam String name) {
    sqreenInfo.dumpRule(name)
  }
}
