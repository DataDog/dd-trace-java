package datadog.smoketest.springboot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestSuite {

  @GetMapping("/hello")
  public String hello() {
    return "world";
  }
}
