package datadog.trace.instrumentation.springsecurity5

import datadog.trace.api.GlobalTracer
import org.springframework.security.core.userdetails.User
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

import static datadog.appsec.api.user.User.setUser
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.REGISTER
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.SUCCESS
import static datadog.trace.instrumentation.springsecurity5.TestEndpoint.SDK
import static java.util.Collections.emptyMap


@Controller
class UserController {

  private final UserDetailsManager userDetailsManager

  UserController(UserDetailsManager userDetailsManager) {
    this.userDetailsManager = userDetailsManager
  }

  @RequestMapping("/success")
  @ResponseBody
  String success() {
    SpringBootBasedTest.controller(SUCCESS) {
      SUCCESS.body
    }
  }

  @PostMapping("/register")
  @ResponseBody
  void register(
    @RequestParam("username") String username,
    @RequestParam("password") String password,
    Model model) {
    SpringBootBasedTest.controller(REGISTER) {
      userDetailsManager.createUser(
        User.withUsername(username).password("{noop}" + password).roles("USER").build())
      model.addAttribute("username", username)
    }
  }

  @PostMapping("/sdk")
  @ResponseBody
  String sdk(@RequestParam(name = "sdkEvent", defaultValue = "login.success") String event, @RequestParam(name = "sdkUser", required = false) String sdkUser) {
    SpringBootBasedTest.controller(SDK) {
      switch (event) {
        case "login.success":
          GlobalTracer.getEventTracker().trackLoginSuccessEvent(sdkUser, emptyMap())
          break
        case "login.failure":
          GlobalTracer.getEventTracker().trackLoginFailureEvent(sdkUser, false, emptyMap())
          break
        case "setUser":
          setUser(sdkUser, emptyMap())
          break
        default:
          GlobalTracer.getEventTracker().trackCustomEvent(event, emptyMap())
          break
      }
      return "OK"
    }
  }
}
