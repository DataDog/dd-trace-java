package io.sqreen.testapp.sampleapp

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestMapping

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static org.springframework.web.bind.annotation.RequestMethod.POST

@Controller
class RegistrationController {

  @Autowired
  private UserDetailsManager userDetailsManager

  @RequestMapping(path = '/signup/', method = POST)
  String register(String username, String password,
    HttpServletRequest request, HttpServletResponse response) {
    this.userDetailsManager.createUser(
      new User(username, password, [new SimpleGrantedAuthority('ROLE_USER')]))

    if (IntegrationTestHelpers.isInteTestRequest(request)) {
      response.setStatus(201)
      response.flushBuffer() // will generate exception later: response already committed
    }

    'redirect:/'
  }
}
