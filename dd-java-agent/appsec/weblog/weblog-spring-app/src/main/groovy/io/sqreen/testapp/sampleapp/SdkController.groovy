package io.sqreen.testapp.sampleapp

import io.sqreen.agent.sdk.Sqreen
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping('/sdk')
class SdkController {

  @RequestMapping(path = '/identify')
  String secret(String login, String mood) {
    Sqreen.user()
      .authKey('login', login)
      .trait('mood', mood)
      .identify()

    "identify for login = $login with trait { mood = $mood }"
  }

  @RequestMapping(path = '/login')
  String login(String login, String mood, String success) {
    Sqreen.user()
      .authKey('login', login)
      .trait('mood', mood)
      .trackLogin(Boolean.parseBoolean(success))

    "login = $success for login = $login with trait { mood = $mood }"
  }

  @RequestMapping(path = '/signup')
  String signup(String login, String mood) {
    Sqreen.user()
      .authKey('login', login)
      .trait('mood', mood)
      .trackSignup()

    "signup for login = $login with trait { mood = $mood }"
  }

  @RequestMapping(path = '/track')
  String signup(String event, String login, String propertyName, String propertyValue, String timestamp) {
    def isTracked = Sqreen.event(event)
      .authKey('login', login)
      .property(propertyName, propertyValue)
      .timestamp(timestamp != null ? new Date(Long.parseLong(timestamp)) : null)
      .track()

    "track event $event with login = $login, properties = { $propertyName = $propertyValue } at timestamp = $timestamp, result = $isTracked"
  }
}
