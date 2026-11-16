package test.normalizedroute

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** Minimal app with the route shapes that need presence logic in the normalized route. */
@SpringBootApplication
class NormalizedRouteAppConfig {
}

@RestController
class NormalizedRouteController {

  @RequestMapping(value = '/regex/{id:\\d+}', method = RequestMethod.GET)
  String regex() {
    'ok'
  }

  @RequestMapping(value = '/nested/{code:[0-9]{3}}', method = RequestMethod.GET)
  String nestedRegex() {
    'ok'
  }

  @RequestMapping(value = '/files/*.txt', method = RequestMethod.GET)
  String singleStar() {
    'ok'
  }

  @RequestMapping(value = '/static/**', method = RequestMethod.GET)
  String trailingDoubleStar() {
    'ok'
  }

  @RequestMapping(value = '/mixed/{id}/**', method = RequestMethod.GET)
  String namedAndDoubleStar() {
    'ok'
  }

  @RequestMapping(value = '/star/*/**', method = RequestMethod.GET)
  String starAndDoubleStar() {
    'ok'
  }

  @RequestMapping(value = '/mid/**/end', method = RequestMethod.GET)
  String midPathDoubleStar() {
    'ok'
  }
}
