package datadog.trace.instrumentation.springsecurity6


import org.springframework.security.core.userdetails.User
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.REGISTER
import static datadog.trace.instrumentation.springsecurity6.TestEndpoint.SUCCESS

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
}
