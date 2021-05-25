package io.sqreen.testapp.sampleapp

import io.sqreen.agent.sdk.Sqreen
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SuController {

  @RequestMapping(path = '/su/{userId}/{target}')
  String su(@PathVariable String userId, @PathVariable String target, String timestamp) {

    Sqreen.event('get')
      .timestamp(timestamp != null ? new Date(Long.parseLong(timestamp)) : null)
      .track()

    Sqreen.identify(["username": "admin"])

    Sqreen.event('su')
      .authKey('login', userId)
      .property("user", userId)
      .property("target", target)
      .timestamp(timestamp != null ? new Date(Long.parseLong(timestamp)) : null)
      .track()

    Sqreen.event('access')
      .authKey('login', userId)
      .timestamp(timestamp != null ? new Date(Long.parseLong(timestamp)) : null)
      .track()
  }
}
