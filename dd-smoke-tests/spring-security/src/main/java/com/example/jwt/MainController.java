package com.example.jwt;

import java.security.Principal;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MainController {

  @GetMapping("/read")
  public String read(Principal userPrincipal, JwtAuthenticationToken jwtToken) {
    System.out.println("Token attributes: " + jwtToken.getTokenAttributes());
    return "SuccesfulRead for " + userPrincipal.getName();
  }
}
