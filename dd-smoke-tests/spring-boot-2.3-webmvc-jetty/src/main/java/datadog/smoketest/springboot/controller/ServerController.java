package datadog.smoketest.springboot.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("hello")
@Validated
public class ServerController {
  @RequestMapping("/not-found")
  public String notFound() {
    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "entity not found");
  }

  @RequestMapping("/not-here")
  public ResponseEntity notHere() {
    return new ResponseEntity("not here", HttpStatus.NOT_FOUND);
  }
}
