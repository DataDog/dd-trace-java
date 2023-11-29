package datadog.smoketest.appsec.springbootsecurity.controllers;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class UserController {
  private final UserDetailsManager userDetailsManager;

  public UserController(UserDetailsManager userDetailsManager) {
    this.userDetailsManager = userDetailsManager;
  }

  @PostMapping("/register")
  public String register(
      @RequestParam("username") String username,
      @RequestParam("password") String password,
      Model model) {
    userDetailsManager.createUser(
        User.withUsername(username).password("{noop}" + password).roles("USER").build());
    model.addAttribute("username", username);
    return "created";
  }
}
